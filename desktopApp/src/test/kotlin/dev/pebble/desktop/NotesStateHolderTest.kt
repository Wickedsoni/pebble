package dev.pebble.desktop

import dev.pebble.core.calendar.CalendarAgenda
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
import dev.pebble.desktop.app.pages.NotesEvent
import dev.pebble.desktop.app.pages.NotesStateHolder
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WP E3c-3: the Notes page in the state-holder pattern, and the History of a note. Now: Sun 4 Oct 2026, 10:00, Kolkata. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesStateHolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 4, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val db = DatabaseFactory.inMemory()
    private val journal = ChangeJournal(db)
    private val notes = NoteRepository(db, journal)
    private val reminders = ReminderRepository(db, journal)
    private val agenda = CalendarAgenda(CalendarRepository(db, journal), reminders) { zone }
    private val engine = ReminderEngine(reminders, EventBus(), clock = { now }, minuteOfDay = { 10 * 60 })

    /** What the page asked the app to do (the app's own actions publish the bus events). */
    private val added = mutableListOf<String>()
    private val completed = mutableListOf<Long>()

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

    private fun TestScope.holder() = env().let { (env, scope) ->
        NotesStateHolder(
            notes,
            addNote = { text -> added += text; notes.add(text, now) },
            completeNote = { id -> completed += id; notes.archive(id, now) },
            env = env,
            scope = scope,
        )
    }

    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 4, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theFirstStateListsOpenNotesNewestFirstWithTimesInTheAppZone() = runTest {
        notes.add("buy milk", at(9))
        notes.add("call the bank", at(9, 30))
        val h = holder()
        advanceUntilIdle()
        val s = h.state.value
        assertEquals("2 open", s.countLabel)
        assertEquals(listOf("call the bank", "buy milk"), s.notes.map { it.text })
        assertTrue(s.notes.first().timeLabel.lowercase() == "4 oct, 9:30 am", s.notes.first().timeLabel)
        assertTrue(s.notes.all { it.uid != null })
    }

    @Test
    fun addGoesThroughTheAppAndTheListUpdates() = runTest {
        val h = holder()
        advanceUntilIdle()
        assertEquals("0 open", h.state.value.countLabel)
        h.onEvent(NotesEvent.Add("passport number"))
        advanceUntilIdle()
        assertEquals(listOf("passport number"), added)
        assertEquals(listOf("passport number"), h.state.value.notes.map { it.text })
    }

    @Test
    fun completeArchivesTheNoteAndItLeavesThePage() = runTest {
        val id = notes.add("buy milk", at(9))
        val h = holder()
        advanceUntilIdle()
        h.onEvent(NotesEvent.Complete(id))
        advanceUntilIdle()
        assertEquals(listOf(id), completed)
        assertEquals("0 open", h.state.value.countLabel)
    }

    @Test
    fun aNoteAddedElsewhereShowsUp() = runTest {
        val h = holder()
        advanceUntilIdle()
        notes.add("from Quick Add", at(9, 45)) // Quick Add and voice write through the repository
        advanceUntilIdle()
        assertEquals(listOf("from Quick Add"), h.state.value.notes.map { it.text })
    }

    @Test
    fun theHistoryOfANoteShowsItsOldTextAndRestoreBringsItBack() = runTest {
        val id = notes.add("buy milk", at(9))
        notes.update(id, "buy oat milk", at(9, 5)) // an edit (today only from sync or a restore)
        val page = holder()
        advanceUntilIdle()
        val row = page.state.value.notes.single()

        val (env, scope) = env()
        val h = HistoryStateHolder(ChangeHistory(db, journal), journal, agenda, engine, env, scope)
        h.onEvent(HistoryEvent.Open(SyncTable.NOTE, assertNotNull(row.uid), row.text))
        advanceUntilIdle()
        assertEquals(listOf("Text: “buy milk” → “buy oat milk”"), h.state.value.versions.single().changes)

        h.onEvent(HistoryEvent.Restore(0))
        advanceUntilIdle()
        assertEquals(listOf("buy milk"), page.state.value.notes.map { it.text })
        assertEquals(emptyList(), journal.verify())
    }
}
