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
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** State of disabled rules and deleted one-offs must not outlive them; `upcoming` must respect the active window. */
class ReminderEnginePruneTest {
    @AfterTest
    fun deleteTheDatabase() = listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val dbFile = File.createTempFile("pebble-prune", ".db").apply { delete() }
    private val repo = ReminderRepository(DatabaseFactory.create(dbFile)).apply { seedDefaults() }

    /** Starts at 12:00 on a day boundary; the local minute of day follows the clock (UTC). */
    private var now = 20_000 * day + 12 * 60 * minute
    private val bus = EventBus()

    private fun engine(nudge: NudgePolicy? = null, minuteOfDay: (Long) -> Int = { (it / minute % 1440).toInt() }) =
        ReminderEngine(repo, bus, clock = { now }, minuteOfDay = minuteOfDay, nudge = nudge)

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

    @Test
    fun upcomingFindsTheWindowStartAcrossAClockChange() {
        val london = ZoneId.of("Europe/London") // 29 Mar 2026 01:00 UTC: the clocks go from 01:00 to 02:00
        now = ZonedDateTime.of(2026, 3, 28, 22, 50, 0, 0, london).toInstant().toEpochMilli() // window 08:00-23:00
        val e = engine(minuteOfDay = { Instant.ofEpochMilli(it).atZone(london).let { t -> t.hour * 60 + t.minute } })
        val expected = ZonedDateTime.of(2026, 3, 29, 8, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(expected, e.upcoming().first { it.key == "rule:water" }.dueAt, "08:00 local, not 09:00")
    }

    @Test
    fun upcomingKeepsADueTimeInsideAWindowThatCrossesMidnight() {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("UPDATE reminder_rule SET active_from_minute = 1320, active_to_minute = 360 WHERE id = 'water'")
            }
        }
        now = 20_000 * day + (23 * 60 + 30) * minute // 23:30, window 22:00-06:00
        val e = engine()
        assertEquals(now + 60 * minute, e.upcoming().first { it.key == "rule:water" }.dueAt, "00:30 is inside the window")
        now = 20_000 * day + (5 * 60 + 30) * minute // 05:30: due 06:30, after the window closes
        assertEquals(20_000 * day + 22 * 60 * minute, engine().upcoming().first { it.key == "rule:water" }.dueAt)
    }

    @Test
    fun aWindowStartInTheSpringForwardGapOpensAtTheFirstMinuteAfterTheGap() {
        setWaterWindow(fromMinute = 90, toMinute = 1380) // 01:30-23:00; on 29 Mar 2026 London has no 01:00-01:59
        val london = ZoneId.of("Europe/London")
        now = ZonedDateTime.of(2026, 3, 28, 23, 30, 0, 0, london).toInstant().toEpochMilli() // due 00:30, closed
        val e = engine(minuteOfDay = { Instant.ofEpochMilli(it).atZone(london).let { t -> t.hour * 60 + t.minute } })
        val expected = ZonedDateTime.of(2026, 3, 29, 2, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(expected, e.upcoming().first { it.key == "rule:water" }.dueAt, "02:00 BST, the first minute in the window")
    }

    @Test
    fun upcomingFindsTheWindowStartAfterTheClocksGoBack() {
        // A guard: the repeated hour must not move an 08:00 start (the corrected guess is right at once).
        val london = ZoneId.of("Europe/London") // 25 Oct 2026 02:00 BST: the clocks go back to 01:00
        now = ZonedDateTime.of(2026, 10, 24, 22, 50, 0, 0, london).toInstant().toEpochMilli()
        val e = engine(minuteOfDay = { Instant.ofEpochMilli(it).atZone(london).let { t -> t.hour * 60 + t.minute } })
        val expected = ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(expected, e.upcoming().first { it.key == "rule:water" }.dueAt)
    }

    private fun setWaterWindow(fromMinute: Int, toMinute: Int) =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("UPDATE reminder_rule SET active_from_minute = $fromMinute, active_to_minute = $toMinute WHERE id = 'water'")
            }
        }
}
