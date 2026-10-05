package dev.pebble.core

import dev.pebble.core.brain.NudgeArm
import dev.pebble.core.brain.NudgeContext
import dev.pebble.core.brain.NudgeEpisode
import dev.pebble.core.brain.NudgeEpisodes
import dev.pebble.core.brain.NudgeIpsEvaluator
import dev.pebble.core.brain.NudgePolicy
import dev.pebble.core.brain.NudgeStore
import dev.pebble.core.brain.NudgeTarget
import dev.pebble.core.brain.asTarget
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.ReminderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NudgeIpsEvaluatorTest {
    private val minute = 60_000L
    private val t0 = 1_000_000_000L
    private val water = "rule:water"

    private fun decided(arm: NudgeArm, at: Long, p: Double = 0.5, key: String = water, ctx: String = "WATER:2:0") =
        PebbleEvent.NudgeDecided(key, ctx, arm.name, p, at)

    private fun due(at: Long, key: String = water) = PebbleEvent.ReminderDue(key, ReminderKind.WATER, "Water", at)

    private fun acted(action: ReminderAction, at: Long, key: String = water) = PebbleEvent.ReminderActed(
        key,
        ReminderKind.WATER,
        action,
        null,
        at,
    )

    private fun rewards(vararg events: PebbleEvent) = NudgeIpsEvaluator.episodesOf(events.toList()).let { r ->
        r.episodes.map { it.reward } to
            r.skipped
    }

    // ------------------------------------------------------------------ pairing decisions with reactions

    @Test
    fun rewardsMatchHowTheEngineScoresAReaction() {
        assertEquals(listOf(1.0) to 0, rewards(decided(NudgeArm.NOW, t0), due(t0), acted(ReminderAction.DONE, t0 + 5 * minute)))
        // A 30-minute wait: the reaction time counts from when it showed, not from the decision.
        assertEquals(
            listOf(0.6) to 0,
            rewards(decided(NudgeArm.WAIT_30, t0), due(t0 + 30 * minute), acted(ReminderAction.DONE, t0 + 50 * minute)),
        )
        assertEquals(listOf(0.3) to 0, rewards(decided(NudgeArm.NOW, t0), due(t0), acted(ReminderAction.SNOOZED, t0 + minute)))
        assertEquals(listOf(0.0) to 0, rewards(decided(NudgeArm.NOW, t0), due(t0), acted(ReminderAction.DISMISSED, t0 + minute)))
    }

    @Test
    fun noReactionFor30MinutesIsIgnoredOnlyIfTheAppRanThatLong() {
        val ignored = rewards(decided(NudgeArm.NOW, t0), due(t0), PebbleEvent.ActiveHour(9, t0 + 31 * minute))
        assertEquals(listOf(0.0) to 0, ignored)
        // A late reaction does not count: the engine had already scored the nudge as ignored.
        assertEquals(listOf(0.0) to 0, rewards(decided(NudgeArm.NOW, t0), due(t0), acted(ReminderAction.DONE, t0 + 45 * minute)))
        // The log ends, or the app started again (it may have closed at minute 1): not known, skipped.
        assertEquals(emptyList<Double>() to 1, rewards(decided(NudgeArm.NOW, t0), due(t0), PebbleEvent.ActiveHour(9, t0 + 20 * minute)))
        assertEquals(emptyList<Double>() to 1, rewards(decided(NudgeArm.NOW, t0), due(t0), PebbleEvent.AppStarted(t0 + 40 * minute)))
    }

    @Test
    fun aRestartBeforeTheReactionSkipsTheDecision() {
        val r = rewards(
            decided(NudgeArm.WAIT_10, t0),
            PebbleEvent.AppStopping(t0 + 2 * minute),
            PebbleEvent.AppStarted(t0 + 3 * minute),
            decided(NudgeArm.NOW, t0 + 3 * minute),
            due(t0 + 3 * minute),
            acted(ReminderAction.DONE, t0 + 4 * minute),
        )
        assertEquals(listOf(1.0) to 1, r, "only the decision after the restart has an outcome")
    }

    @Test
    fun reactionsToOtherRemindersDoNotCount() {
        val r = rewards(
            decided(NudgeArm.NOW, t0),
            decided(NudgeArm.NOW, t0, key = "rule:eyes", ctx = "EYES:2:0"),
            due(t0),
            due(t0, key = "rule:eyes"),
            acted(ReminderAction.DISMISSED, t0 + minute, key = "rule:eyes"),
            acted(ReminderAction.DONE, t0 + 2 * minute),
        )
        assertEquals(listOf(1.0, 0.0) to 0, r)
    }

    @Test
    fun invalidEntriesAreSkipped() {
        val r = rewards(
            decided(NudgeArm.NOW, t0, p = 0.0),
            PebbleEvent.NudgeDecided(water, "WATER:2:0", "WAIT_5", 0.5, t0),
            PebbleEvent.NudgeDecided(water, "WATER:9:0", "NOW", 0.5, t0),
            due(t0),
            acted(ReminderAction.DONE, t0 + minute),
        )
        assertEquals(emptyList<Double>() to 3, r)
        assertNull(NudgeContext.parse("WATER:2"))
        assertEquals(NudgeContext.of(ReminderKind.EYES, 21, busy = true), NudgeContext.parse("EYES:5:1"))
    }

    // ------------------------------------------------------------------ the estimators

    private val ctx = NudgeContext.of(ReminderKind.WATER, 9, busy = false)

    private fun ep(arm: NudgeArm, p: Double, r: Double) = NudgeEpisode(ctx, arm, p, r, t0)

    @Test
    fun estimatesMatchAHandComputedExample() {
        val data =
            NudgeEpisodes(listOf(ep(NudgeArm.NOW, 0.8, 1.0), ep(NudgeArm.NOW, 0.8, 0.0), ep(NudgeArm.WAIT_30, 0.2, 0.6)), skipped = 2)
        // Target: NOW half the time, WAIT_30 half the time. Weights 0.625, 0.625, 2.5.
        val target = NudgeTarget { _, a -> if (a == NudgeArm.NOW || a == NudgeArm.WAIT_30) 0.5 else 0.0 }
        val r = NudgeIpsEvaluator.evaluate(data, target)
        assertEquals(3, r.episodes)
        assertEquals(2, r.skipped)
        assertEquals(1.6 / 3, r.observed, 1e-12)
        assertEquals((0.625 + 2.5 * 0.6) / 3, r.ips, 1e-12)
        assertEquals((0.625 + 2.5 * 0.6) / 3.75, r.snips, 1e-12)
        assertEquals(3.75 * 3.75 / (0.625 * 0.625 * 2 + 6.25), r.ess, 1e-12)
        assertEquals(2.5, r.maxWeight, 1e-12)
    }

    @Test
    fun theLoggingPolicyItselfScoresWhatWasObserved() {
        val probs = mapOf(NudgeArm.NOW to 0.7, NudgeArm.WAIT_10 to 0.2, NudgeArm.WAIT_30 to 0.1)
        val rnd = Random(3)
        val eps = List(500) {
            val arm = pick(probs, rnd)
            ep(arm, probs.getValue(arm), if (rnd.nextDouble() < 0.5) 1.0 else 0.0)
        }
        val r = NudgeIpsEvaluator.evaluate(NudgeEpisodes(eps, 0), NudgeTarget { _, a -> probs.getValue(a) })
        assertEquals(r.observed, r.snips, 1e-12)
        assertEquals(500.0, r.ess, 1e-9)
        assertEquals(0.0, NudgeIpsEvaluator.evaluate(NudgeEpisodes(emptyList(), 4), r.let { NudgeTarget.always(NudgeArm.NOW) }).ess)
    }

    /**
     * A simulated user (as in NudgePolicyTest): busy 12:00–16:00, where only a 30-minute wait lands. The log
     * comes from a policy that mostly reminds on time; SNIPS recovers the true value of "wait 30 in the busy
     * window, on time elsewhere", which the log almost never did.
     */
    @Test
    fun snipsRecoversTheTrueValueOfAPolicyThatWasRarelyRun() {
        val busy = NudgeContext.of(ReminderKind.WATER, 13, busy = false)
        val morning = NudgeContext.of(ReminderKind.WATER, 9, busy = false)
        fun meanReward(c: NudgeContext, a: NudgeArm) = when {
            c == busy -> when (a) { NudgeArm.NOW -> 0.1; NudgeArm.WAIT_10 -> 0.3; NudgeArm.WAIT_30 -> 0.9 }
            else -> if (a == NudgeArm.NOW) 0.9 else 0.5
        }
        val logging = mapOf(NudgeArm.NOW to 0.8, NudgeArm.WAIT_10 to 0.1, NudgeArm.WAIT_30 to 0.1)
        val rnd = Random(11)
        val eps = List(4000) {
            val c = if (rnd.nextBoolean()) busy else morning
            val arm = pick(logging, rnd)
            NudgeEpisode(c, arm, logging.getValue(arm), if (rnd.nextDouble() < meanReward(c, arm)) 1.0 else 0.0, t0)
        }
        val candidate = NudgeTarget { c, a -> if (a == (if (c == busy) NudgeArm.WAIT_30 else NudgeArm.NOW)) 1.0 else 0.0 }
        val r = NudgeIpsEvaluator.evaluate(NudgeEpisodes(eps, 0), candidate)
        val truth = 0.5 * 0.9 + 0.5 * 0.9
        assertTrue(abs(r.snips - truth) < 0.03, "SNIPS ${r.snips} should be near $truth")
        assertTrue(abs(r.ips - truth) < 0.06, "IPS ${r.ips} should be near $truth")
        assertTrue(r.observed < 0.6, "what ran did worse: ${r.observed}")
        assertTrue(r.passesGate(), "a better policy with ESS ${r.ess} passes")
        // Always on time is worse than what ran: the gate refuses it.
        assertFalse(NudgeIpsEvaluator.evaluate(NudgeEpisodes(eps, 0), NudgeTarget.always(NudgeArm.WAIT_10)).passesGate())
    }

    @Test
    fun aBetterPolicyWithTooLittleEvidenceDoesNotPassTheGate() {
        val eps = List(40) { ep(NudgeArm.WAIT_30, 0.1, 1.0) } + List(360) { ep(NudgeArm.NOW, 0.9, 0.5) }
        val r = NudgeIpsEvaluator.evaluate(NudgeEpisodes(eps, 0), NudgeTarget.always(NudgeArm.WAIT_30))
        assertTrue(r.snips > r.observed)
        assertEquals(40.0, r.ess, 1e-9)
        assertFalse(r.passesGate())
    }

    private fun pick(probs: Map<NudgeArm, Double>, rnd: Random): NudgeArm {
        var u = rnd.nextDouble()
        for ((arm, p) in probs) {
            u -= p
            if (u < 0) return arm
        }
        return probs.keys.last()
    }

    // ------------------------------------------------------------------ against the real engine and log

    /**
     * Remembers each reward the policy learned: alpha grows by the reward, alpha + beta by 1. With no quiet
     * hours the starting belief is the prior: NOW Beta(3, 1), the waits Beta(1, 1.5).
     */
    private class RecordingStore : NudgeStore {
        val learned = mutableListOf<String>()
        private val last = mutableMapOf<String, Double>()
        override fun load() = emptyMap<String, Pair<Double, Double>>()
        override fun save(key: String, alpha: Double, beta: Double) {
            val before = last[key] ?: if (key.endsWith("|NOW")) 3.0 else 1.0
            learned += "$key|${"%.3f".format(alpha - before)}"
            last[key] = alpha
        }
    }

    @Test
    fun theEvaluatorFindsTheSameRewardsTheEngineLearnedFrom() = runTest {
        val db = DatabaseFactory.inMemory()
        val logger = EventLogger(db)
        val bus = EventBus()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { logger.log(it) } }
        val repo = ReminderRepository(db).apply { seedDefaults() }
        val store = RecordingStore()
        val policy = NudgePolicy(store, random = Random(5))
        var now = t0
        val minuteOfDay = { t: Long -> ((t / minute) % (24 * 60)).toInt() }
        val engine = ReminderEngine(repo, bus, clock = { now }, minuteOfDay = minuteOfDay, nudge = policy)
        bus.publish(PebbleEvent.AppStarted(now))
        val rnd = Random(9)
        repeat(4 * 24 * 60) {
            now += minute
            engine.tick()
            if (minuteOfDay(now) % 60 == 0) bus.publish(PebbleEvent.ActiveHour(minuteOfDay(now) / 60, now))
            for (r in engine.active.value) {
                when (rnd.nextInt(30)) {
                    0 -> engine.act(r.key, ReminderAction.DONE)
                    1 -> engine.act(r.key, ReminderAction.SNOOZED)
                    2 -> engine.act(r.key, ReminderAction.DISMISSED)
                }
            }
        }
        bus.publish(PebbleEvent.ActiveHour(minuteOfDay(now) / 60, now)) // the app ran until the end

        val data = NudgeIpsEvaluator(db).episodes()
        val found = data.episodes.map { "${it.ctx.key}|${it.arm.name}|${"%.3f".format(it.reward)}" }
        assertTrue(found.size >= 50, "enough decisions: ${found.size}")
        assertTrue(found.any { it.endsWith("|0.000") } && found.any { it.endsWith("|1.000") })
        assertEquals(store.learned.sorted(), found.sorted())
        assertTrue(data.skipped <= 3, "only decisions still open at the end are skipped: ${data.skipped}")

        val report = NudgeIpsEvaluator(db).evaluate(policy.asTarget())
        assertEquals(found.size, report.episodes)
        assertTrue(report.ess > 0 && report.snips in 0.0..1.0)
    }
}
