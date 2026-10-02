package dev.pebble.core

import dev.pebble.core.brain.InMemoryNudgeStore
import dev.pebble.core.brain.NudgeArm
import dev.pebble.core.brain.NudgeContext
import dev.pebble.core.brain.NudgePolicy
import dev.pebble.core.brain.SqlNudgeStore
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.ReminderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NudgePolicyTest {
    /**
     * A simulated user, busy 12:00–16:00: a nudge right away gets skipped, one 30 minutes later gets
     * done. The rest of the day, an on-time nudge is done at once and a delayed one only eventually.
     */
    private fun reaction(ctx: NudgeContext, arm: NudgeArm): Double = when {
        ctx.hourBucket == 3 -> when (arm) { NudgeArm.NOW -> 0.0; NudgeArm.WAIT_10 -> 0.3; NudgeArm.WAIT_30 -> 1.0 }
        else -> if (arm == NudgeArm.NOW) 1.0 else 0.6
    }

    @Test
    fun learnsToWaitInTheBusyWindowAndNotElsewhere() {
        val policy = NudgePolicy(InMemoryNudgeStore(), random = Random(7))
        val busyAfternoon = NudgeContext.of(ReminderKind.WATER, 13, busy = false)
        val morning = NudgeContext.of(ReminderKind.WATER, 9, busy = false)
        repeat(200) {
            // ~200 days, one water nudge in each window
            for (ctx in listOf(busyAfternoon, morning)) {
                val arm = policy.choose(ctx).arm
                policy.learn(ctx, arm, reaction(ctx, arm))
            }
        }
        val afternoon = (1..100).count { policy.choose(busyAfternoon).arm == NudgeArm.WAIT_30 }
        val onTime = (1..100).count { policy.choose(morning).arm == NudgeArm.NOW }
        assertTrue(afternoon >= 85, "should wait 30 min in the busy window, did $afternoon/100")
        assertTrue(onTime >= 85, "should stay on time in the morning, did $onTime/100")
    }

    @Test
    fun startsOnTimeAndQuietHoursOnlyBias() {
        val fresh = NudgePolicy(InMemoryNudgeStore(), random = Random(1))
        val ctx = NudgeContext.of(ReminderKind.STRETCH, 10, busy = false)
        assertTrue(fresh.expected(ctx).maxBy { it.value }.key == NudgeArm.NOW, "a new Pebble reminds on time")
        val quiet = NudgePolicy(InMemoryNudgeStore(), quietHours = { setOf(13, 14) }, random = Random(1))
        val e = quiet.expected(NudgeContext.of(ReminderKind.STRETCH, 13, busy = false))
        assertTrue(e.getValue(NudgeArm.NOW) < e.getValue(NudgeArm.WAIT_10), "learned quiet hours lean towards waiting")
    }

    @Test
    fun beliefsSurviveARestart() {
        val db = DatabaseFactory.inMemory()
        val ctx = NudgeContext.of(ReminderKind.EYES, 20, busy = true)
        NudgePolicy(SqlNudgeStore(db)).apply { repeat(5) { learn(ctx, NudgeArm.WAIT_10, 1.0) } }
        val again = NudgePolicy(SqlNudgeStore(db))
        assertEquals(6.0 / 7.5, again.expected(ctx).getValue(NudgeArm.WAIT_10), 1e-9) // prior Beta(1, 1.5) + 5 wins
    }

    // ------------------------------------------------------------------ inside the reminder engine

    private val minute = 60_000L
    private var now = 1_000_000_000L
    private val repo = ReminderRepository(DatabaseFactory.inMemory()).apply { seedDefaults() }

    /** A policy that has learned to always wait 30 minutes. */
    private fun waiter() = NudgePolicy(InMemoryNudgeStore(), random = Random(3)).apply {
        for (kind in ReminderKind.entries) repeat(300) { learn(NudgeContext.of(kind, 12, false), NudgeArm.WAIT_30, 1.0) }
        for (kind in ReminderKind.entries) repeat(300) { learn(NudgeContext.of(kind, 12, false), NudgeArm.NOW, 0.0) }
    }

    @Test
    fun aWaitIsCappedAndOneOffsAreNeverDelayed() = runTest {
        val events = mutableListOf<PebbleEvent>()
        val bus = EventBus()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { events += it } }
        val engine = ReminderEngine(repo, bus, clock = { now }, minuteOfDay = { 12 * 60 }, nudge = waiter())
        repo.addOneOff("Call mom", now + 60 * minute)
        fun advance(m: Int) { now += m * minute; engine.tick() }
        fun active() = engine.active.value.map { it.key }.toSet()

        advance(60) // eyes + water rules (60 min) and the one-off are all due now
        assertEquals(setOf("once:1"), active(), "the one-off fires on time; the rules wait")
        val decided = events.filterIsInstance<PebbleEvent.NudgeDecided>().single { it.key == "rule:eyes" }
        assertEquals("WAIT_30" to "rule:eyes", decided.arm to decided.key)
        advance(29)
        assertTrue("rule:eyes" !in active())
        advance(1)
        assertTrue("rule:eyes" in active(), "after one 30-minute wait it shows, whatever the policy thinks")
        advance(30)
        assertTrue("rule:eyes" in active(), "and is never deferred again in the same cycle")
    }

    @Test
    fun reactionsTeachThePolicy() {
        val store = InMemoryNudgeStore()
        val policy = NudgePolicy(store, random = Random(5))
        val engine = ReminderEngine(repo, EventBus(), clock = { now }, minuteOfDay = { 9 * 60 }, nudge = policy)
        fun advance(m: Int) { now += m * minute; engine.tick() }
        advance(60) // eyes due; a fresh policy starts on time
        val ctx = NudgeContext.of(ReminderKind.EYES, 9, false)
        val before = policy.expected(ctx).getValue(NudgeArm.NOW)
        advance(2)
        engine.act("rule:eyes", ReminderAction.DONE)
        assertTrue(policy.expected(ctx).getValue(NudgeArm.NOW) > before, "done within 15 minutes raises NOW")
        assertTrue(store.data.isNotEmpty())
    }
}
