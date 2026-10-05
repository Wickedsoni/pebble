package dev.pebble.desktop

import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.calendar.Ics
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.desktop.app.pages.AgendaStateHolder
import dev.pebble.desktop.app.pages.CalendarPageEvent
import dev.pebble.desktop.app.pages.CalendarStateHolder
import dev.pebble.desktop.app.pages.Repeat
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP E2: the Calendar page's state holder and the Today page's Agenda card. Now: Sun 4 Oct 2026, 10:00, Kolkata. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarStateHolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 4, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val db = DatabaseFactory.inMemory()
    private val calendar = CalendarRepository(db)
    private val reminders = ReminderRepository(db)
    private val agenda = CalendarAgenda(calendar, reminders) { zone }
    private val engine = ReminderEngine(reminders, EventBus(), clock = { now }, minuteOfDay = { 10 * 60 })

    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val default = d
        override val io = d
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.env(): Pair<AppEnv, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AppEnv(clock, { zone }, TestDispatchers(dispatcher)) to CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
    }

    private fun TestScope.holder(): CalendarStateHolder = env().let { (env, scope) ->
        CalendarStateHolder(calendar, agenda, engine, env, scope)
    }

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theFirstStateIsThisMonthWithTodaySelected() = runTest {
        calendar.save(CalendarEvent("x", "Standup", at(1, 9), at(1, 10), zone.id, rrule = "FREQ=WEEKLY;BYDAY=MO,TH"), at = 1)
        val s = holder().state.value
        assertEquals("October 2026", s.monthTitle)
        assertEquals(42, s.cells.size)
        assertEquals(LocalDate.of(2026, 9, 28), s.cells.first().date, "weeks start on Monday")
        val today = s.cells.single { it.today }
        assertEquals(LocalDate.of(2026, 10, 4) to true, today.date to today.selected)
        assertEquals(1, s.cells.single { it.date == LocalDate.of(2026, 10, 5) }.events)
        assertEquals(0, s.cells.single { it.date == LocalDate.of(2026, 10, 6) }.events)
        assertTrue(s.selectedTitle.startsWith("Today"))
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun addingOnTheSelectedDayUsesItsDateTheRepeatAndTheReminder() = runTest {
        val h = holder()
        h.onEvent(CalendarPageEvent.Select(LocalDate.of(2026, 10, 9)))
        h.onEvent(CalendarPageEvent.SetRepeat(Repeat.WEEKLY))
        h.onEvent(CalendarPageEvent.SetRemind(60))
        h.onEvent(CalendarPageEvent.Add("Dentist 5pm for 30 min"))
        advanceUntilIdle()
        val e = calendar.live().single()
        assertEquals(at(9, 17) to at(9, 17, 30), e.startAt to e.endAt)
        assertEquals("FREQ=WEEKLY;BYDAY=FR" to 60, e.rrule to e.remindMinutes)
        val row = h.state.value.selected.single()
        assertEquals("Dentist" to "Weekly", row.title to row.repeatLabel)
        assertEquals("5:00 pm – 5:30 pm", row.timeLabel.lowercase())
        assertNull(row.warning)
        assertEquals(Repeat.NONE, h.state.value.repeat, "the repeat choice is for one event")
        assertEquals("Added “Dentist”.", h.state.value.message)
    }

    @Test
    fun aDayInTheTextWinsOverTheSelectedDayAndNoTimeIsAllDay() = runTest {
        val h = holder()
        h.onEvent(CalendarPageEvent.Add("Diwali 8 nov"))
        advanceUntilIdle()
        val e = calendar.live().single()
        assertTrue(e.allDay)
        assertNull(e.remindMinutes)
        assertEquals("November 2026", h.state.value.monthTitle, "the page goes to the new event")
        assertEquals("All day", h.state.value.selected.single().timeLabel)
        h.onEvent(CalendarPageEvent.Add("5pm"))
        advanceUntilIdle()
        assertTrue(h.state.value.message!!.startsWith("Type a title"))
    }

    @Test
    fun monthsMoveAndTodayComesBack() = runTest {
        val h = holder()
        h.onEvent(CalendarPageEvent.NextMonth)
        h.onEvent(CalendarPageEvent.NextMonth)
        advanceUntilIdle()
        assertEquals("December 2026", h.state.value.monthTitle)
        h.onEvent(CalendarPageEvent.PreviousMonth)
        advanceUntilIdle()
        assertEquals("November 2026", h.state.value.monthTitle)
        h.onEvent(CalendarPageEvent.GoToToday)
        advanceUntilIdle()
        assertEquals("October 2026", h.state.value.monthTitle)
    }

    @Test
    fun anEventFromQuickAddAppearsAndDeleteRemovesItAndItsReminder() = runTest {
        val h = holder()
        calendar.save(CalendarEvent("q", "Call", at(4, 18), at(4, 19), zone.id, remindMinutes = 15), at = now)
        agenda.eventChanged("q", now)
        advanceUntilIdle()
        assertEquals(listOf("Call"), h.state.value.selected.map { it.title })
        assertEquals(1, reminders.pendingOneOffs().size)
        h.onEvent(CalendarPageEvent.Delete("q"))
        advanceUntilIdle()
        assertTrue(h.state.value.selected.isEmpty())
        assertTrue(reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun skippingOneDayKeepsTheOtherDaysAndItsReminderGoes() = runTest {
        calendar.save(CalendarEvent("s", "Standup", at(4, 11), at(4, 11, 15), zone.id, rrule = "FREQ=DAILY", remindMinutes = 15), at = now)
        agenda.eventChanged("s", now)
        val h = holder()
        val row = h.state.value.selected.single()
        assertTrue(row.canSkip)
        h.onEvent(CalendarPageEvent.SkipDay(row.uid, row.occurrenceAt))
        advanceUntilIdle()
        assertTrue(h.state.value.selected.isEmpty(), "today is skipped")
        assertEquals(listOf(at(4, 11)), calendar.live().single().exdates)
        assertEquals(1, h.state.value.cells.single { it.date == LocalDate.of(2026, 10, 5) }.events, "tomorrow stays")
        assertEquals(listOf(at(5, 10, 45)), reminders.pendingOneOffs().map { it.dueAt }, "only tomorrow's reminder is left")
        assertTrue(h.state.value.message!!.startsWith("Skipped"))
    }

    @Test
    fun skippingADayOfAnAllDaySeries() = runTest {
        calendar.save(CalendarEvent("w", "Gym day", at(4, 0), at(5, 0), zone.id, allDay = true, rrule = "FREQ=WEEKLY"), at = 1)
        val h = holder()
        h.onEvent(CalendarPageEvent.SkipDay("w", h.state.value.selected.single().occurrenceAt))
        advanceUntilIdle()
        assertTrue(h.state.value.selected.isEmpty())
        assertEquals(1, h.state.value.cells.single { it.date == LocalDate.of(2026, 10, 11) }.events)
    }

    @Test
    fun aSingleEventCannotBeSkipped() = runTest {
        calendar.save(CalendarEvent("o", "Once", at(4, 12), at(4, 13), zone.id), at = 1)
        assertEquals(false, holder().state.value.selected.single().canSkip)
    }

    @Test
    fun anUnsupportedRuleShowsAWarning() = runTest {
        calendar.save(CalendarEvent("y", "Birthday", at(4, 0), at(5, 0), zone.id, allDay = true, rrule = "FREQ=YEARLY"), at = 1)
        val row = holder().state.value.selected.single()
        assertEquals("Repeats", row.repeatLabel)
        assertTrue(row.warning!!.contains("shown once"))
    }

    @Test
    fun importThenExportGoesThroughFiles() = runTest {
        val dir = Files.createTempDirectory("pebble-ics")
        val inFile = dir.resolve("in.ics")
        val source = listOf(
            CalendarEvent("a", "Standup", at(5, 9), at(5, 9, 15), zone.id, rrule = "FREQ=DAILY;COUNT=3"),
            CalendarEvent("b", "Birthday", at(6, 0), at(7, 0), zone.id, allDay = true, rrule = "FREQ=YEARLY"),
        )
        Files.writeString(inFile, Ics.write(source, now))
        val h = holder()
        h.onEvent(CalendarPageEvent.Import(inFile))
        advanceUntilIdle()
        assertEquals(
            "Imported 2 events from in.ics. Timed events remind you 15 min before. 1 repeat in a way Pebble shows only once.",
            h.state.value.message,
        )
        assertEquals(
            setOf("a" to 15, "b" to null),
            calendar.live().map {
                it.uid to it.remindMinutes
            }.toSet(),
            "the page's reminder, timed events only",
        )
        h.onEvent(CalendarPageEvent.SetRemind(null))
        h.onEvent(CalendarPageEvent.Import(inFile))
        advanceUntilIdle()
        assertEquals(2, calendar.live().size, "the same file again replaces, it does not duplicate")
        assertTrue(calendar.live().all { it.remindMinutes == null }, "\"No reminder\" before an import: none")
        assertTrue(h.state.value.message!!.contains("No reminders."))

        val outFile = dir.resolve("out.ics")
        h.onEvent(CalendarPageEvent.Export(outFile))
        advanceUntilIdle()
        assertEquals("Exported 2 events to out.ics.", h.state.value.message)
        assertEquals(source.toSet(), Ics.parse(Files.readString(outFile), zone).events.toSet())

        h.onEvent(CalendarPageEvent.Import(dir.resolve("missing.ics")))
        advanceUntilIdle()
        assertTrue(h.state.value.message!!.startsWith("That did not work"))
    }

    /** WP E3, spec D5: a deleted event never comes back, also when you import the same file again. */
    @Test
    fun aDeletedEventIsNotImportedAgain() = runTest {
        val dir = Files.createTempDirectory("pebble-ics")
        val inFile = dir.resolve("in.ics")
        Files.writeString(
            inFile,
            Ics.write(
                listOf(
                    CalendarEvent("a", "Standup", at(5, 9), at(5, 9, 15), zone.id),
                    CalendarEvent("b", "Review", at(5, 10), at(5, 11), zone.id),
                ),
                now,
            ),
        )
        val h = holder()
        h.onEvent(CalendarPageEvent.Import(inFile))
        advanceUntilIdle()
        h.onEvent(CalendarPageEvent.Delete("a"))
        advanceUntilIdle()
        h.onEvent(CalendarPageEvent.Import(inFile))
        advanceUntilIdle()
        assertEquals(listOf("b"), calendar.live().map { it.uid })
        assertTrue(
            h.state.value.message!!.startsWith("Imported 1 event from in.ics. 1 event was deleted before and was not imported again."),
            h.state.value.message,
        )
    }

    @Test
    fun theAgendaCardShowsWhatIsLeftOfTodayThenTomorrow() = runTest {
        calendar.save(CalendarEvent("past", "Breakfast", at(4, 8), at(4, 9), zone.id), at = 1)
        calendar.save(CalendarEvent("on", "Workshop", at(4, 9, 30), at(4, 11), zone.id), at = 1)
        calendar.save(CalendarEvent("later", "Dinner", at(4, 20), at(4, 21), zone.id), at = 1)
        calendar.save(CalendarEvent("tmrw", "Standup", at(5, 9), at(5, 9, 15), zone.id), at = 1)
        calendar.save(CalendarEvent("far", "Trip", at(8, 9), at(8, 10), zone.id), at = 1)
        val (env, scope) = env()
        val rows = AgendaStateHolder(calendar, agenda, env, scope, everyMinute = false).state.value.rows
        assertEquals(listOf("Workshop", "Dinner", "Standup"), rows.map { it.title })
        assertEquals(listOf(true, false, false), rows.map { it.now })
        assertEquals(listOf("Today", "Today", "Tomorrow"), rows.map { it.day })
    }
}
