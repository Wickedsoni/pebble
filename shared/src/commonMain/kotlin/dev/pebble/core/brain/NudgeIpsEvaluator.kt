package dev.pebble.core.brain

import dev.pebble.core.event.PebbleEvent
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json
import kotlin.math.max

/** One logged nudge decision and the reward it earned, as the reminder engine scored it. */
data class NudgeEpisode(
    val ctx: NudgeContext,
    val arm: NudgeArm,
    /** The probability the running policy had of picking [arm] (logged in `nudge_decided`). */
    val propensity: Double,
    val reward: Double,
    val atMillis: Long,
)

/** The episodes in the log, and how many decisions had no known outcome (app closed, log ends, bad entry). */
data class NudgeEpisodes(val episodes: List<NudgeEpisode>, val skipped: Int)

/** A policy to evaluate offline: the probability it picks [arm] in [ctx]. */
fun interface NudgeTarget {
    fun probability(ctx: NudgeContext, arm: NudgeArm): Double

    companion object {
        fun always(arm: NudgeArm) = NudgeTarget { _, a -> if (a == arm) 1.0 else 0.0 }
    }
}

/** [NudgePolicy] with today's beliefs, held fixed: one Monte-Carlo estimate per context. */
fun NudgePolicy.asTarget(draws: Int = 2_000): NudgeTarget {
    val cache = mutableMapOf<String, Map<NudgeArm, Double>>()
    return NudgeTarget { ctx, arm -> cache.getOrPut(ctx.key) { probabilities(ctx, draws) }.getValue(arm) }
}

/**
 * What a target policy would have earned on the logged decisions.
 * - [observed]: the mean reward of what actually ran (the current policy, measured on-policy).
 * - [ips]: inverse propensity scoring, mean of w·r with w = target / propensity. Unbiased, high variance.
 * - [snips]: self-normalised IPS, Σ w·r / Σ w. A small bias, much less variance; the gate uses it.
 * - [ess]: effective sample size, (Σ w)² / Σ w². How many episodes the estimate is really worth.
 */
data class IpsReport(
    val episodes: Int,
    val skipped: Int,
    val observed: Double,
    val ips: Double,
    val snips: Double,
    val ess: Double,
    val maxWeight: Double,
) {
    /** The gate for a change to [NudgePolicy] priors or rewards: not worse than what runs today, on enough evidence. */
    fun passesGate(minEss: Double = NudgeIpsEvaluator.MIN_ESS): Boolean = ess >= minEss && snips >= observed
}

/**
 * Offline evaluator for the nudge policy (WP C4, ADR 0016). It reads the raw `nudge_decided` rows (with the
 * propensity that was logged), finds the reaction to each, and estimates how a candidate policy would have
 * done on the same history, without showing it to you. It only reads `event_log`; it never deletes a row.
 */
class NudgeIpsEvaluator(private val db: PebbleDatabase) {
    /** The episodes since [sinceMillis] (raw rows: pairing needs single entries, not daily counts). */
    fun episodes(sinceMillis: Long = 0): NudgeEpisodes {
        val rows = db.historyQueries.eventsOfTypesSince(TYPES, sinceMillis).executeAsList()
        return episodesOf(rows.mapNotNull { runCatching { json.decodeFromString(PebbleEvent.serializer(), it.payload) }.getOrNull() })
    }

    fun evaluate(target: NudgeTarget, sinceMillis: Long = 0): IpsReport = evaluate(episodes(sinceMillis), target)

    companion object {
        /** The gate needs at least this many effective episodes (plan, WP C4). */
        const val MIN_ESS = 200.0

        /** No reaction this long after it showed: the engine scores the nudge as ignored. Same as `ReminderEngine`. */
        const val IGNORED_AFTER = 30 * 60_000L

        /** Decisions, reactions, and the entries that show whether the app was running (starts, stops, active hours). */
        private val TYPES = listOf("nudge_decided", "reminder_due", "reminder_acted", "app_started", "app_stopping", "active_hour")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pairs each decision with the reward the engine gave it ([NudgePolicy.rewardFor]). [events] are in time order.
         * - The reminder shows at the first `reminder_due` of the key at or after the decision.
         * - The reward comes from the first `reminder_acted` of the key after that, within 30 minutes; with none,
         *   the nudge was ignored (0).
         * - A decision is skipped when its outcome is not known: the app stopped or started again before the
         *   reaction (the engine forgets its decisions then), no later entry shows that 30 minutes passed with the
         *   app running, or the entry is not valid (unknown arm or context, propensity not in (0, 1]).
         */
        fun episodesOf(events: List<PebbleEvent>): NudgeEpisodes {
            val out = mutableListOf<NudgeEpisode>()
            var skipped = 0
            events.forEachIndexed { i, e ->
                if (e !is PebbleEvent.NudgeDecided) return@forEachIndexed
                val episode = episodeAt(events, i, e)
                if (episode == null) skipped++ else out += episode
            }
            return NudgeEpisodes(out, skipped)
        }

        private fun episodeAt(events: List<PebbleEvent>, i: Int, d: PebbleEvent.NudgeDecided): NudgeEpisode? {
            val ctx = NudgeContext.parse(d.context) ?: return null
            val arm = NudgeArm.entries.firstOrNull { it.name == d.arm } ?: return null
            if (!(d.propensity > 0.0 && d.propensity <= 1.0)) return null
            var shownAt: Long? = null
            for (j in i + 1 until events.size) {
                val e = events[j]
                val shown = shownAt
                if (shown != null && e.atMillis - shown >= IGNORED_AFTER) {
                    // Ignored for 30 minutes, if the app ran that long: a start means it may have closed before.
                    return if (e is PebbleEvent.AppStarted) null else episode(ctx, arm, d, NudgePolicy.rewardFor(null, 30.0))
                }
                when {
                    e is PebbleEvent.AppStarted || e is PebbleEvent.AppStopping -> return null

                    e is PebbleEvent.NudgeDecided && e.key == d.key -> return null

                    e is PebbleEvent.ReminderDue && e.key == d.key && shown == null -> shownAt = e.atMillis

                    e is PebbleEvent.ReminderActed && e.key == d.key && shown != null -> {
                        val minutes = (e.atMillis - shown) / 60_000.0
                        return episode(ctx, arm, d, NudgePolicy.rewardFor(e.action, minutes))
                    }
                }
            }
            return null // the log ends before we know
        }

        private fun episode(ctx: NudgeContext, arm: NudgeArm, d: PebbleEvent.NudgeDecided, reward: Double) =
            NudgeEpisode(ctx, arm, d.propensity, reward, d.atMillis)

        fun evaluate(data: NudgeEpisodes, target: NudgeTarget): IpsReport {
            val eps = data.episodes
            if (eps.isEmpty()) return IpsReport(0, data.skipped, 0.0, 0.0, 0.0, 0.0, 0.0)
            var sumW = 0.0
            var sumW2 = 0.0
            var sumWR = 0.0
            var maxW = 0.0
            for (e in eps) {
                val w = target.probability(e.ctx, e.arm) / e.propensity
                sumW += w
                sumW2 += w * w
                sumWR += w * e.reward
                maxW = max(maxW, w)
            }
            return IpsReport(
                episodes = eps.size,
                skipped = data.skipped,
                observed = eps.sumOf { it.reward } / eps.size,
                ips = sumWR / eps.size,
                snips = if (sumW > 0) sumWR / sumW else 0.0,
                ess = if (sumW2 > 0) sumW * sumW / sumW2 else 0.0,
                maxWeight = maxW,
            )
        }
    }
}
