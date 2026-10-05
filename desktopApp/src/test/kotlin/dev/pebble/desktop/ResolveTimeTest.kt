package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DefaultDispatchers
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Remind me at …" on a fixed clock: which day and which half of the day a time lands on. */
class ResolveTimeTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDateTime.of(2026, 10, 4, 0, 0).toLocalDate()

    private fun appAt(hour: Int, minute: Int = 0): PebbleApp {
        val now = today.atTime(hour, minute).atZone(zone).toInstant()
        return PebbleApp(DatabaseFactory.inMemory(), AppEnv(Clock.fixed(now, zone), { zone }, DefaultDispatchers))
    }

    /** "5 baje": no am/pm, so either 5:00 or 17:00. */
    private fun fiveBaje(dayOffset: Int? = null) = QuickCommand.RemindAt("chai", 5, 0, dayOffset, flexibleHalfDay = true)

    @Test
    fun fiveBajeBefore5amIsThisMorning() {
        assertEquals(today.atTime(5, 0), appAt(3).resolve(fiveBaje()))
    }

    @Test
    fun fiveBajeAfter5amIsThisEvening() {
        assertEquals(today.atTime(17, 0), appAt(9).resolve(fiveBaje()))
    }

    @Test
    fun fiveBajeAfter5pmRollsToTomorrowMorning() {
        val app = appAt(18, 30)
        assertEquals(today.plusDays(1).atTime(5, 0), app.resolve(fiveBaje()))
        assertTrue(app.describe(fiveBaje()).contains("tomorrow at"), app.describe(fiveBaje()))
    }

    @Test
    fun exactlyAtTheTimeCountsAsPassed() {
        assertEquals(today.atTime(17, 0), appAt(5).resolve(fiveBaje()))
    }

    @Test
    fun dayOffsetPicksTheFirstFreeTimeOnThatDay() {
        // "kal 5 baje": tomorrow, and 5:00 has not passed yet tomorrow.
        assertEquals(today.plusDays(1).atTime(5, 0), appAt(18).resolve(fiveBaje(dayOffset = 1)))
    }

    @Test
    fun dayOffsetTodayKeepsTheDayEvenWhenBothTimesPassed() {
        // "aaj 5 baje" at 18:00: no roll-over to tomorrow; the later candidate is kept.
        assertEquals(today.atTime(17, 0), appAt(18).resolve(fiveBaje(dayOffset = 0)))
    }

    @Test
    fun fixedTimeThatPassedRollsToTomorrow() {
        val nineAm = QuickCommand.RemindAt("standup", 9, 0, null)
        assertEquals(today.plusDays(1).atTime(9, 0), appAt(10).resolve(nineAm))
        assertEquals(today.atTime(9, 0), appAt(8).resolve(nineAm))
    }

    @Test
    fun afternoonHourIsNeverMovedToTheMorning() {
        // 17 baje is already a 24-hour time: only one candidate.
        val seventeen = QuickCommand.RemindAt("call", 17, 0, null, flexibleHalfDay = true)
        assertEquals(today.plusDays(1).atTime(17, 0), appAt(18).resolve(seventeen))
    }

    @Test
    fun theReminderIsStoredAtTheResolvedInstant() {
        val app = appAt(9)
        app.execute(fiveBaje())
        val due = app.reminders.pendingOneOffs().single().dueAt
        assertEquals(today.atTime(17, 0).atZone(zone).toInstant().toEpochMilli(), due)
    }
}
