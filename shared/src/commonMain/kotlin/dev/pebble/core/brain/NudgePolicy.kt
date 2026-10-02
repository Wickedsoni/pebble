package dev.pebble.core.brain

import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** When to show a repeating reminder that just became due. */
enum class NudgeArm(val waitMinutes: Int) { NOW(0), WAIT_10(10), WAIT_30(30) }

/**
 * What the policy sees: which reminder, roughly when (six 4-hour buckets), and whether you're busy
 * (fullscreen app / presenting). Coarse on purpose: few contexts learn fast from a few nudges a day.
 */
data class NudgeContext(val kind: ReminderKind, val hourBucket: Int, val busy: Boolean) {
    val key: String get() = "${kind.name}:$hourBucket:${if (busy) 1 else 0}"

    companion object {
        fun of(kind: ReminderKind, hour: Int, busy: Boolean) = NudgeContext(kind, hour / 4, busy)
    }
}

/** Beta(alpha, beta) belief per (context, arm), persisted so learning survives restarts. */
interface NudgeStore {
    fun load(): Map<String, Pair<Double, Double>>
    fun save(key: String, alpha: Double, beta: Double)
}

class InMemoryNudgeStore : NudgeStore {
    val data = mutableMapOf<String, Pair<Double, Double>>()
    override fun load() = data.toMap()
    override fun save(key: String, alpha: Double, beta: Double) { data[key] = alpha to beta }
}

/**
 * The habit brain's first piece of real reinforcement learning: a contextual Thompson-sampling bandit.
 * For each context it keeps a Beta belief of "how well does a nudge land" per arm, samples from each
 * and picks the best sample — trying alternatives exactly as often as it's unsure about them.
 *
 * Reward (from how you react, [rewardFor]): done soon = 1, done late = 0.6, snoozed = 0.3,
 * skipped or ignored = 0. Priors start every context on NOW (today's behaviour), and hours Pebble
 * already learned you skip ([quietHours]) start leaning towards waiting.
 */
class NudgePolicy(
    private val store: NudgeStore,
    private val quietHours: () -> Set<Int> = { emptySet() },
    private val random: Random = Random.Default,
) {
    private val beliefs: MutableMap<String, Pair<Double, Double>> = store.load().toMutableMap()

    data class Choice(val arm: NudgeArm, val propensity: Double)

    fun choose(ctx: NudgeContext): Choice {
        val samples = NudgeArm.entries.associateWith { arm -> belief(ctx, arm).let { (a, b) -> sampleBeta(a, b) } }
        val arm = samples.maxBy { it.value }.key
        return Choice(arm, propensity(ctx, arm))
    }

    /** Learn from one outcome: [reward] in 0..1 nudges the belief for (context, arm). */
    fun learn(ctx: NudgeContext, arm: NudgeArm, reward: Double) {
        val (a, b) = belief(ctx, arm)
        val r = reward.coerceIn(0.0, 1.0)
        val next = (a + r) to (b + 1 - r)
        beliefs[keyOf(ctx, arm)] = next
        store.save(keyOf(ctx, arm), next.first, next.second)
    }

    /** Mean success estimate per arm — for the Memory page ("I wait a bit around 2 pm"). */
    fun expected(ctx: NudgeContext): Map<NudgeArm, Double> =
        NudgeArm.entries.associateWith { arm -> belief(ctx, arm).let { (a, b) -> a / (a + b) } }

    /** How many reactions this context has seen (beyond the priors), across all arms. */
    fun observations(ctx: NudgeContext): Double = NudgeArm.entries.sumOf { arm ->
        val learned = beliefs[keyOf(ctx, arm)]?.takeIf { it.first > 0 || it.second > 0 } ?: return@sumOf 0.0
        val p = prior(ctx, arm)
        (learned.first + learned.second) - (p.first + p.second)
    }

    /** Forget what was learned for every context whose key starts with [contextPrefix] (e.g. "WATER:3"). */
    fun resetContext(contextPrefix: String) {
        beliefs.keys.filter { it.startsWith(contextPrefix) }.forEach {
            beliefs.remove(it)
            store.save(it, 0.0, 0.0)
        }
    }

    private fun belief(ctx: NudgeContext, arm: NudgeArm): Pair<Double, Double> =
        beliefs[keyOf(ctx, arm)]?.takeIf { it.first > 0 || it.second > 0 } ?: prior(ctx, arm)

    private fun prior(ctx: NudgeContext, arm: NudgeArm): Pair<Double, Double> {
        val quiet = (ctx.hourBucket * 4 until ctx.hourBucket * 4 + 4).count { it in quietHours() } >= 2
        return when {
            arm == NudgeArm.NOW && quiet -> 1.0 to 3.0
            arm == NudgeArm.NOW -> 3.0 to 1.0 // start where Pebble is today: remind on time
            quiet -> 2.0 to 2.0
            else -> 1.0 to 1.5
        }
    }

    /** Probability Thompson sampling picks [arm] here (Monte-Carlo), logged for offline evaluation (IPS). */
    private fun propensity(ctx: NudgeContext, arm: NudgeArm, draws: Int = 200): Double {
        val b = NudgeArm.entries.associateWith { belief(ctx, it) }
        var wins = 0
        repeat(draws) {
            val pick = b.maxBy { (_, ab) -> sampleBeta(ab.first, ab.second) }.key
            if (pick == arm) wins++
        }
        return (wins + 0.5) / (draws + 1.0)
    }

    private fun keyOf(ctx: NudgeContext, arm: NudgeArm) = "${ctx.key}|${arm.name}"

    // Beta(a, b) = X / (X + Y), X ~ Gamma(a), Y ~ Gamma(b).
    private fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a)
        val y = sampleGamma(b)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Marsaglia–Tsang; shape < 1 boosted with U^(1/shape). */
    private fun sampleGamma(shape: Double): Double {
        if (shape < 1) return sampleGamma(shape + 1) * random.nextDouble().pow(1 / shape)
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian()
                v = 1 + c * x
            } while (v <= 0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * kotlin.math.PI * u2)
    }

    companion object {
        /** How a reaction to a nudge scores. [minutesAfterShown] is from when it appeared to the reaction. */
        fun rewardFor(action: ReminderAction?, minutesAfterShown: Double): Double = when (action) {
            ReminderAction.DONE -> if (minutesAfterShown <= 15) 1.0 else 0.6
            ReminderAction.SNOOZED -> 0.3
            ReminderAction.DISMISSED, null -> 0.0 // null: ignored for 30 minutes
        }

    }
}
