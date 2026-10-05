package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DefaultDispatchers
import dev.pebble.desktop.core.UiPort
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every quick-add command: what the pet says, and whether it can be taken back. Fixed clock: Sun 4 Oct 2026, 9:30. */
class CommandExecutorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 4, 9, 30)
    private val app =
        PebbleApp(DatabaseFactory.inMemory(), AppEnv(Clock.fixed(now.atZone(zone).toInstant(), zone), { zone }, DefaultDispatchers))
    private var opened: String? = null

    init {
        app.bindUi(
            object : UiPort {
                override fun notify(title: String, message: String) = Unit

                override fun openPage(page: String) {
                    opened = page
                }
            },
        )
    }

    /** One of every subtype. */
    private val commands = listOf(
        QuickCommand.AddNote("buy milk"),
        QuickCommand.RememberFact("my sister's birthday is 12 June"),
        QuickCommand.LogWater(2),
        QuickCommand.SetInterval(ReminderKind.WATER, 45, null),
        QuickCommand.RemindIn("chai", 30),
        QuickCommand.RemindAt("call mom", 19, 0, null),
        QuickCommand.ShowUpcoming,
        QuickCommand.ShowNotes,
        QuickCommand.TellTime,
        QuickCommand.Chitchat("tum cute ho", "general_quirky"),
        QuickCommand.Unsupported("book a cab", "transport_taxi"),
        QuickCommand.OpenPage("reminders"),
        QuickCommand.SearchMemory("the project", "what did I note about the project"),
        QuickCommand.AddEvent("Dentist", hour = 17, dayOffset = 1),
    )

    /** No `else`: a new command type doesn't compile until this test says whether it has an undo. */
    private fun hasUndo(cmd: QuickCommand): Boolean = when (cmd) {
        is QuickCommand.AddNote, is QuickCommand.RemindIn, is QuickCommand.RemindAt, is QuickCommand.AddEvent -> true
        is QuickCommand.RememberFact, is QuickCommand.LogWater, is QuickCommand.SetInterval -> false
        QuickCommand.ShowUpcoming, QuickCommand.ShowNotes, QuickCommand.TellTime -> false
        is QuickCommand.Chitchat, is QuickCommand.Unsupported, is QuickCommand.OpenPage, is QuickCommand.SearchMemory -> false
    }

    @Test
    fun everyCommandSaysSomethingAndOnlyCreationsCanBeUndone() {
        for (cmd in commands) {
            val executed = app.executor.execute(cmd)
            assertTrue(executed.line.text.isNotBlank(), "$cmd says something")
            assertEquals(hasUndo(cmd), executed.undo != null, "undo for $cmd")
            assertTrue(app.executor.describe(cmd).isNotBlank(), "preview for $cmd")
        }
    }

    @Test
    fun replies() {
        fun say(cmd: QuickCommand) = app.executor.execute(cmd).line.text
        assertEquals("Saved to your notes.", say(QuickCommand.AddNote("buy milk")))
        assertEquals("Got it, I'll remember that.", say(QuickCommand.RememberFact("I like chai")))
        assertEquals("2 of 8 glasses today.", say(QuickCommand.LogWater(2)))
        assertTrue(say(QuickCommand.SetInterval(ReminderKind.WATER, 45, null)).startsWith("Every 45 min · "))
        assertEquals("I'll remind you in 1h 30m.", say(QuickCommand.RemindIn("chai", 90)))
        assertTrue(say(QuickCommand.RemindAt("call mom", 19, 0, null)).startsWith("I'll remind you at 7:00"))
        assertTrue(say(QuickCommand.RemindAt("standup", 9, 0, null)).startsWith("I'll remind you tomorrow at 9:00"))
        assertTrue(say(QuickCommand.TellTime).startsWith("It's 9:30"))
        assertTrue(say(QuickCommand.ShowNotes).contains("buy milk"))
        assertTrue(say(QuickCommand.ShowUpcoming).startsWith("Next: "))
    }

    @Test
    fun undoTakesBackOnlyItsOwnCommand() {
        val first = app.executor.execute(QuickCommand.AddNote("first"))
        app.executor.execute(QuickCommand.AddNote("second"))
        // The old shared `lastUndo` would now point at "second"; each result keeps its own.
        assertNotNull(first.undo).invoke()
        assertEquals(listOf("second"), app.notes.recent().map { it.text })
    }

    @Test
    fun reminderUndoRemovesTheReminder() {
        val executed = app.executor.execute(QuickCommand.RemindIn("chai", 30))
        assertEquals(now.plusMinutes(30).atZone(zone).toInstant().toEpochMilli(), app.reminders.pendingOneOffs().single().dueAt)
        assertNotNull(executed.undo).invoke()
        assertTrue(app.reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun openPageGoesThroughTheUi() {
        val executed = app.executor.execute(QuickCommand.OpenPage("notes"))
        assertEquals("notes", opened)
        assertNull(executed.undo)
    }

    /** WP E2: an event from Quick Add, its reminder 15 min before, and its undo. */
    @Test
    fun anEventIsAddedWithAReminderAndUndoRemovesBoth() {
        val executed = app.executor.execute(QuickCommand.AddEvent("Dentist", hour = 17))
        assertEquals("added to your calendar: dentist, today, 5:00 pm–6:00 pm.", executed.line.text.lowercase())
        val e = app.calendar.live().single()
        assertEquals(now.withHour(17).withMinute(0).atZone(zone).toInstant().toEpochMilli(), e.startAt)
        assertEquals("Asia/Kolkata" to 15, e.tz to e.remindMinutes)
        val reminder = app.reminders.pendingOneOffs().single()
        assertEquals(now.withHour(16).withMinute(45).atZone(zone).toInstant().toEpochMilli(), reminder.dueAt)
        assertNotNull(executed.undo).invoke()
        assertTrue(app.calendar.live().isEmpty())
        assertTrue(app.reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun eventTimesResolveLikeReminders() {
        fun start(cmd: QuickCommand.AddEvent) =
            java.time.Instant.ofEpochMilli(app.executor.event(cmd).startAt).atZone(zone).toLocalDateTime()
        assertEquals(
            now.withHour(21).withMinute(0),
            start(QuickCommand.AddEvent("Standup", hour = 9, flexibleHalfDay = true)),
            "9 has passed: 21:00",
        )
        assertEquals(now.plusDays(1).withHour(8).withMinute(0), start(QuickCommand.AddEvent("Gym", hour = 8)), "8:00 has passed: tomorrow")
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0), start(QuickCommand.AddEvent("Diwali", month = 11, dayOfMonth = 8)))
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 0, 0),
            start(QuickCommand.AddEvent("New year", month = 1, dayOfMonth = 1)),
            "the next 1 Jan",
        )
        val allDay = app.executor.event(QuickCommand.AddEvent("Trip", dayOffset = 6))
        assertTrue(allDay.allDay)
        assertNull(allDay.remindMinutes, "no reminder for an all-day event")
        assertEquals(24 * 60 * 60_000L, allDay.endAt - allDay.startAt)
    }
}
