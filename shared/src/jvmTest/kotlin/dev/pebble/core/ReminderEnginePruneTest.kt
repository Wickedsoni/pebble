package dev.pebble.core

import dev.pebble.core.brain.InMemoryNudgeStore
import dev.pebble.core.brain.NudgePolicy
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** State of disabled rules and deleted one-offs must not outlive them; `upcoming` must respect the active window. */
class ReminderEnginePruneTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val repo = ReminderRepository(DatabaseFactory.inMemory()).apply { seedDefaults() }

    /** Starts at 12:00 on a day boundary; the local minute of day follows the clock (UTC). */
    private var now = 20_000 * day + 12 * 60 * minute
    private val bus = EventBus()

    private fun engine(nudge: NudgePolicy? = null) =
        ReminderEngine(repo, bus, clock = { now }, minuteOfDay = { (it / minute % 1440).toInt() }, nudge = nudge)

    private fun ReminderEngine.advance(minutes: Int) {
        now += minutes * minute
        tick()
    }

    private fun ReminderEngine.keys() = active.value.map { it.key }.toSet()

    private fun setEnabled(id: String, enabled: Boolean) = repo.updateRule(id, 60, Strictness.NORMAL, enabled)

    @Test
    fun aReEnabledRuleGetsANewSnooze() {
        val e = engine()
        e.advance(60)
        e.act("rule:water", ReminderAction.SNOOZED, snoozeMinutes = 180)
        setEnabled("water", false)
        e.advance(1)
        setEnabled("water", true)
        e.advance(1)
        assertTrue("rule:water" in e.keys(), "the old snooze must not outlive the disabled rule")
    }

    @Test
    fun aReEnabledRuleForgetsItsSkips() {
        val e = engine()
        e.advance(60)
        e.act("rule:eyes", ReminderAction.DISMISSED) // the gap would double
        setEnabled("eyes", false)
        e.advance(1)
        setEnabled("eyes", true)
        e.advance(60)
        assertTrue("rule:eyes" in e.keys(), "skips of a disabled rule are forgotten")
    }

    @Test
    fun aReEnabledRuleMakesANewPolicyDecision() {
        val seen = CopyOnWriteArrayList<PebbleEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { bus.events.collect { seen += it } }
        fun decisions() = seen.filterIsInstance<PebbleEvent.NudgeDecided>().count { it.key == "rule:water" }
        val e = engine(NudgePolicy(InMemoryNudgeStore(), random = Random(1)))
        e.advance(60)
        val first = decisions()
        assertTrue(first >= 1)
        setEnabled("water", false)
        e.advance(1)
        setEnabled("water", true)
        e.advance(1)
        assertTrue(decisions() > first, "a re-enabled rule is decided again ($first -> ${decisions()})")
        scope.cancel()
    }

    @Test
    fun upcomingMovesADueTimeOutsideTheWindowToTheNextWindowStart() {
        now = 20_000 * day + (22 * 60 + 50) * minute // 22:50, window 08:00-23:00
        val e = engine()
        // due 23:50 by the interval, but the window is closed until 08:00 the next day
        assertEquals(20_001 * day + 8 * 60 * minute, e.upcoming().first { it.key == "rule:water" }.dueAt)
    }

    @Test
    fun upcomingKeepsADueTimeInsideTheWindow() {
        val e = engine() // 12:00
        assertEquals(now + 60 * minute, e.upcoming().first { it.key == "rule:water" }.dueAt)
    }
}
