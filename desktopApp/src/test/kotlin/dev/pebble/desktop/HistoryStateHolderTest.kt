package dev.pebble.desktop

import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.sync.ChangeHistory
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.SyncTable
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.desktop.app.pages.HistoryEvent
import dev.pebble.desktop.app.pages.HistoryStateHolder
import dev.pebble.desktop.app.pages.RecentlyDeletedEvent
import dev.pebble.desktop.app.pages.RecentlyDeletedStateHolder
import dev.pebble.desktop.app.pages.RemindersStateHolder
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP E3c-2: the History dialog (Calendar and Reminders pages) and the "Recently deleted" card. Now: Sun 4 Oct 2026, 10:00. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryStateHolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 4, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val db = DatabaseFactory.inMemory()
    private val journal = ChangeJournal(db)
    private val history = ChangeHistory(db, journal)
    private val notes = NoteRepository(db, journal)
    private val calendar = CalendarRepository(db, journal)
    private val reminders = ReminderRepository(db, journal)
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

    private fun TestScope.dialog() = env().let { (env, scope) -> HistoryStateHolder(history, journal, agenda, engine, env, scope) }

    private fun TestScope.card() = env().let { (env, scope) ->
        RecentlyDeletedStateHolder(history, notes, reminders, calendar, agenda, engine, env, scope)
    }

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun event(title: String, start: Long, remind: Int? = null) =
        CalendarEvent(uid = "e1", title = title, startAt = start, endAt = start + 3_600_000, tz = zone.id, remindMinutes = remind)

    // ------------------------------------------------------------------ the History dialog

    @Test
    fun theDialogIsClosedFirstAndOpensWithTheVersionsNewestFirst() = runTest {
        calendar.save(event("Dentist", at(6, 17)), at(4, 9))
        calendar.save(event("Dentist (moved)", at(7, 17)), at(4, 9, 30))
        calendar.save(event("Dentist at 6", at(7, 18)), at(4, 9, 45))
        val h = dialog()
        assertFalse(h.state.value.open)

        h.onEvent(HistoryEvent.Open(SyncTable.CALENDAR_EVENT, "e1", "Dentist at 6"))
        advanceUntilIdle()
        val s = h.state.value
        assertTrue(s.open)
        assertEquals("Dentist at 6", s.title)
        assertEquals(2, s.versions.size)
        assertEquals("this device", s.versions[0].deviceLabel)
        assertNull(s.versions[0].lostLabel)
        assertTrue(s.versions[0].whenLabel.lowercase().endsWith("9:30 am"), s.versions[0].whenLabel)
        assertTrue(s.versions[0].changes.any { it.startsWith("Title: “Dentist (moved)” → “Dentist at 6”") }, "${s.versions[0].changes}")
        assertTrue(s.versions[1].changes.any { it.startsWith("Title: “Dentist” →") }, "${s.versions[1].changes}")
        assertTrue(s.versions[1].changes.any { it.startsWith("Start: Tue 6 Oct") }, "${s.versions[1].changes}")

        h.onEvent(HistoryEvent.Close)
        advanceUntilIdle()
        assertFalse(h.state.value.open)
    }

    @Test
    fun restoreMakesTheOldVersionCurrentAndRemakesTheEventReminder() = runTest {
        calendar.save(event("Dentist", at(4, 17), remind = 15), at(4, 9))
        agenda.eventChanged("e1", at(4, 9))
        calendar.save(event("Dentist", at(4, 18), remind = 15), at(4, 9, 30))
        agenda.eventChanged("e1", at(4, 9, 30))
        val h = dialog()
        h.onEvent(HistoryEvent.Open(SyncTable.CALENDAR_EVENT, "e1", "Dentist"))
        advanceUntilIdle()

        h.onEvent(HistoryEvent.Restore(0))
        advanceUntilIdle()
        assertEquals(at(4, 17), calendar.byUid("e1")!!.startAt)
        assertEquals(listOf(at(4, 16, 45)), reminders.pendingOneOffs().map { it.dueAt }, "the reminder moved with the event")
        val s = h.state.value
        assertTrue(s.message!!.startsWith("Restored the version of"), s.message)
        assertEquals(2, s.versions.size, "the restore replaced 6 pm: now that is a version too")
        assertFalse(s.versions.last().canRestore, "the oldest version is the same as now")
        assertEquals(listOf("The same as now."), s.versions.last().changes)
        assertEquals(emptyList(), journal.verify())
    }

    @Test
    fun aRestoreOfADeletedItemSaysToUseACopy() = runTest {
        val id = notes.add("a", at(4, 9))
        notes.update(id, "b", at(4, 9, 1))
        val uid = db.wellnessQueries.noteUid(id).executeAsOne().uid!!
        val h = dialog()
        h.onEvent(HistoryEvent.Open(SyncTable.NOTE, uid, "b"))
        advanceUntilIdle()
        notes.delete(id, at(4, 9, 2))

        h.onEvent(HistoryEvent.Restore(0))
        advanceUntilIdle()
        assertTrue(h.state.value.message!!.contains("Restore as a copy"), h.state.value.message)
        assertTrue(notes.recent().isEmpty())
    }

    @Test
    fun aReminderShowsDoneAsWordsAndOnlyASyncedReminderHasAUid() = runTest {
        val id = reminders.addOneOff("Call mom", at(4, 12), at = at(4, 9))
        reminders.rescheduleOneOff(id, at(4, 13), at(4, 9, 5))
        val (env, scope) = env()
        val page = RemindersStateHolder(reminders, engine, dev.pebble.core.memory.MemoryRepository(db), env, scope)
        advanceUntilIdle()
        val row = page.state.value.oneOffs.single()
        assertNotNull(row.uid)

        val h = dialog()
        h.onEvent(HistoryEvent.Open(SyncTable.ONE_OFF_REMINDER, row.uid, row.title))
        advanceUntilIdle()
        assertTrue(h.state.value.versions.single().changes.single().lowercase().startsWith("time: sun 4 oct, 12:00 pm"))
    }

    @Test
    fun deleteOldVersionsAsksFirstThenRemovesOnlyThisItemsHistory() = runTest {
        val id = notes.add("pin 4821", at(4, 9))
        notes.update(id, "pin: ask me", at(4, 9, 1))
        val other = notes.add("a", at(4, 9))
        notes.update(other, "b", at(4, 9, 1))
        val uid = db.wellnessQueries.noteUid(id).executeAsOne().uid!!
        val h = dialog()
        h.onEvent(HistoryEvent.Open(SyncTable.NOTE, uid, "pin: ask me"))
        advanceUntilIdle()
        assertEquals(1, h.state.value.versions.size)

        h.onEvent(HistoryEvent.AskDelete)
        assertTrue(h.state.value.confirmDelete)
        h.onEvent(HistoryEvent.CancelDelete)
        assertFalse(h.state.value.confirmDelete)
        assertEquals(1, history.versions(SyncTable.NOTE, uid).size, "Cancel keeps them")

        h.onEvent(HistoryEvent.AskDelete)
        h.onEvent(HistoryEvent.ConfirmDelete)
        advanceUntilIdle()
        val s = h.state.value
        assertTrue(s.open)
        assertFalse(s.confirmDelete)
        assertEquals(emptyList(), s.versions)
        assertEquals("Deleted 1 old version (1 history entry).", s.message)
        val otherUid = db.wellnessQueries.noteUid(other).executeAsOne().uid!!
        assertEquals(1, history.versions(SyncTable.NOTE, otherUid).size, "other items keep their history")
        assertEquals("pin: ask me", notes.recent().first { it.id == id }.text, "the note itself does not change")
    }

    // ------------------------------------------------------------------ the "Recently deleted" card

    @Test
    fun theCardListsDeletedItemsAndUpdatesWhenOneIsDeletedElsewhere() = runTest {
        val c = card()
        advanceUntilIdle()
        assertTrue(c.state.value.items.isEmpty())

        val id = notes.add("passport number", at(4, 9))
        calendar.save(event("Dentist", at(6, 17)), at(4, 9))
        notes.delete(id, at(4, 9, 1))
        calendar.delete("e1", at(4, 9, 2))
        advanceUntilIdle()
        assertEquals(listOf("Event", "Note"), c.state.value.items.map { it.kindLabel }, "newest first")
        assertTrue(c.state.value.items[1].deletedLabel.lowercase().startsWith("deleted 4 oct, 9:01"))
    }

    @Test
    fun restoreAsACopyBringsTheEventBackWithItsReminderAndKeepsTheOldOneDeleted() = runTest {
        calendar.save(event("Dentist", at(4, 17), remind = 15), at(4, 9))
        calendar.delete("e1", at(4, 9, 1))
        val c = card()
        advanceUntilIdle()

        c.onEvent(RecentlyDeletedEvent.RestoreCopy(SyncTable.CALENDAR_EVENT, "e1"))
        advanceUntilIdle()
        assertEquals("Restored “Dentist” as a copy.", c.state.value.message)
        val copy = calendar.live().single()
        assertTrue(copy.uid != "e1")
        assertTrue(journal.isDeleted(SyncTable.CALENDAR_EVENT, "e1"))
        assertEquals(listOf(at(4, 16, 45)), reminders.pendingOneOffs().map { it.dueAt }, "the agenda made the copy's reminder")
        assertEquals(1, c.state.value.items.size, "the deleted event stays in the list")
    }

    @Test
    fun clearHistoryAsksFirstAndCancelKeepsIt() = runTest {
        val id = notes.add("a", at(4, 9))
        notes.update(id, "b", at(4, 9, 1))
        val c = card()
        advanceUntilIdle()

        c.onEvent(RecentlyDeletedEvent.AskClear)
        advanceUntilIdle()
        assertTrue(c.state.value.confirmClear)
        c.onEvent(RecentlyDeletedEvent.CancelClear)
        advanceUntilIdle()
        assertFalse(c.state.value.confirmClear)
        assertEquals(1, history.versions(SyncTable.NOTE, db.wellnessQueries.noteUid(id).executeAsOne().uid!!).size)

        c.onEvent(RecentlyDeletedEvent.AskClear)
        c.onEvent(RecentlyDeletedEvent.ConfirmClear)
        advanceUntilIdle()
        assertFalse(c.state.value.confirmClear)
        assertTrue(c.state.value.message!!.startsWith("Deleted 1 history entry."), c.state.value.message)
        assertEquals(0, history.versions(SyncTable.NOTE, db.wellnessQueries.noteUid(id).executeAsOne().uid!!).size)
    }
}
