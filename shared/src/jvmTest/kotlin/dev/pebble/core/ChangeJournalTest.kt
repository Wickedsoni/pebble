package dev.pebble.core

import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.Hlc
import dev.pebble.core.sync.Reconciled
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.db.PebbleDatabase
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The change journal and its guard triggers (WP E3a, spec sections 6 and 9). */
class ChangeJournalTest {
    private val file: File = Files.createTempFile("pebble-journal", ".db").toFile().apply {
        delete()
        listOf("", "-wal", "-shm").forEach { File(path + it).deleteOnExit() }
    }
    private val db: PebbleDatabase = DatabaseFactory.create(file)
    private val journal = ChangeJournal(db)
    private val notes = NoteRepository(db)
    private val reminders = ReminderRepository(db)
    private val calendar = CalendarRepository(db)

    private fun entries(table: String, uid: String) =
        db.journalQueries.entriesOfRow(table, uid).executeAsList().associate { it.field_ to it.value_ }

    private fun noteUid(id: Long) = db.wellnessQueries.noteUid(id).executeAsOne().uid!!

    private fun oneOffUid(id: Long) = db.remindersQueries.oneOffSyncInfo(id).executeAsOne().uid!!

    /** Runs raw SQL on a second connection, as an older Pebble or a bug would. Returns the error, if any. */
    private fun raw(sql: String): String? = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c -> c.createStatement().use { it.execute(sql) } }
    }.exceptionOrNull()?.message

    private fun maxHlc() = db.journalQueries.maxHlc().executeAsOne().hlc!!.let { Hlc.parse(it)!! }

    // ------------------------------------------------------------------ each write path

    @Test
    fun everyWriteToANoteGoesIntoTheJournal() {
        val id = notes.add("buy milk", at = 1_000)
        val uid = noteUid(id)
        assertEquals(
            mapOf("text" to "\"buy milk\"", "created_at" to "1000", "archived" to "0", "deleted_at" to "null"),
            entries("note", uid),
        )
        notes.update(id, "buy milk and bread", at = 2_000)
        notes.archive(id, at = 3_000)
        notes.delete(id, at = 4_000)
        assertEquals(
            mapOf("text" to "\"buy milk and bread\"", "created_at" to "1000", "archived" to "1", "deleted_at" to "4000"),
            entries("note", uid),
        )
        assertEquals(emptyList(), journal.verify())
        assertEquals(4_000L, maxHlc().wallMillis, "the HLC uses the time of the write")
    }

    @Test
    fun everyWriteToAReminderGoesIntoTheJournal() {
        val id = reminders.addOneOff("Call mom", dueAt = 5_000, strictness = Strictness.GENTLE, at = 1_000)
        reminders.rescheduleOneOff(id, dueAt = 9_000, at = 2_000)
        reminders.markOneOffDone(id, at = 3_000)
        val other = reminders.addOneOff("Tea", dueAt = 6_000, at = 1_000)
        reminders.deleteOneOff(other) // no time: SQLite's clock
        assertEquals(
            mapOf("title" to "\"Call mom\"", "due_at" to "9000", "strictness" to "\"GENTLE\"", "done_at" to "3000", "deleted_at" to "null"),
            entries("one_off_reminder", oneOffUid(id)),
        )
        assertTrue(entries("one_off_reminder", oneOffUid(other)).getValue("deleted_at") != "null")
        assertEquals(emptyList(), journal.verify())
    }

    @Test
    fun aReminderThatAnEventMadeIsNotSynced() {
        calendar.save(CalendarEvent("e1", "Dentist", 10_000, 20_000, "Asia/Kolkata", remindMinutes = 15), at = 1)
        assertTrue(reminders.addLinked("Dentist at 5", 9_000, "e1", 10_000, at = 2))
        val id = db.remindersQueries.pendingOneOffs().executeAsList().single().id
        reminders.markOneOffDone(id, at = 3)
        reminders.deletePendingLinked("e1", at = 4)
        assertEquals(emptyList(), db.journalQueries.entriesOfTable("one_off_reminder").executeAsList())
        assertEquals(emptyList(), journal.verify())
    }

    @Test
    fun everyWriteToAnEventGoesIntoTheJournalAndOnlyChangedFieldsGetANewHlc() {
        calendar.save(CalendarEvent("e1", "Dentist", 10_000, 20_000, "Asia/Kolkata"), at = 1_000)
        val created = db.journalQueries.entriesOfRow("calendar_event", "e1").executeAsList().associate { it.field_ to it.hlc }
        assertEquals(12, created.size)
        assertEquals(1, created.values.toSet().size, "one HLC for the whole insert")
        calendar.save(CalendarEvent("e1", "Dentist (moved)", 10_000, 20_000, "Asia/Kolkata", exdates = listOf(10_000)), at = 2_000)
        val after = db.journalQueries.entriesOfRow("calendar_event", "e1").executeAsList().associate { it.field_ to it.hlc }
        assertEquals(setOf("title", "exdates"), after.filter { (f, h) -> h != created[f] }.keys)
        calendar.save(CalendarEvent("e1", "Dentist (moved)", 10_000, 20_000, "Asia/Kolkata", exdates = listOf(10_000)), at = 3_000)
        assertEquals(
            after,
            db.journalQueries.entriesOfRow("calendar_event", "e1").executeAsList().associate {
                it.field_ to it.hlc
            },
            "no change, no entry",
        )
        calendar.delete("e1", at = 4_000)
        assertEquals("4000", entries("calendar_event", "e1")["deleted_at"])
        assertEquals(emptyList(), journal.verify())
    }

    @Test
    fun writesInTheSameMillisecondGetLargerHlcsAlsoFromTwoJournals() {
        val other = NoteRepository(db, ChangeJournal(db)) // a second journal on the same database
        val hlcs = (1..50).map {
            (if (it % 2 == 0) notes else other).add("note $it", at = 7_000)
            maxHlc()
        }
        assertEquals(hlcs.sorted(), hlcs)
        assertEquals(50, hlcs.toSet().size)
        assertTrue(hlcs.all { it.wallMillis == 7_000L })
    }

    // ------------------------------------------------------------------ guard triggers

    @Test
    fun aWriteWithANewHlcButNoJournalEntryIsStopped() {
        val note = noteUid(notes.add("buy milk", at = 1))
        val reminder = oneOffUid(reminders.addOneOff("Call mom", dueAt = 5, at = 1))
        calendar.save(CalendarEvent("e1", "Dentist", 10, 20, "Asia/Kolkata"), at = 1)
        val fake = Hlc(999_999, 0, "zzzz").toString()
        for ((sql, message) in listOf(
            "UPDATE note SET text = 'x', hlc = '$fake' WHERE uid = '$note'" to "note: a synced field changed without a journal entry",
            "UPDATE one_off_reminder SET title = 'x', hlc = '$fake' WHERE uid = '$reminder'" to "one_off_reminder: a synced field changed",
            "UPDATE calendar_event SET title = 'x', hlc = '$fake' WHERE uid = 'e1'" to "calendar_event: a synced field changed",
            "INSERT INTO note(text, created_at, updated_at, uid, hlc) VALUES ('x', 1, 1, 'n9', '$fake')" to
                "note: insert without journal entries",
            "INSERT INTO one_off_reminder(title, due_at, strictness, uid, hlc) VALUES ('x', 1, 'NORMAL', 'r9', '$fake')" to
                "one_off_reminder: insert without",
            "INSERT INTO calendar_event(uid, title, start_at, end_at, tz, updated_at, hlc) VALUES ('e9', 'x', 1, 2, 'UTC', 1, '$fake')" to
                "calendar_event: insert without",
        )) {
            val error = raw(sql)
            assertTrue(error?.contains(message) == true, "$sql -> $error")
        }
        // A local column is not checked.
        assertEquals(null, raw("UPDATE note SET origin_device = 'x', hlc = '$fake' WHERE uid = '$note'"))
    }

    @Test
    fun aDeletedRowNeverComesBack() {
        val id = notes.add("buy milk", at = 1)
        notes.delete(id, at = 2)
        val r = reminders.addOneOff("Call mom", dueAt = 5, at = 1)
        reminders.deleteOneOff(r, at = 2)
        calendar.save(CalendarEvent("e1", "Dentist", 10, 20, "Asia/Kolkata"), at = 1)
        calendar.delete("e1", at = 2)
        for (table in listOf("note", "one_off_reminder", "calendar_event")) {
            val error = raw("UPDATE $table SET deleted_at = NULL")
            assertTrue(error?.contains("$table: a deleted row cannot come back") == true, "$table -> $error")
        }
    }

    // ------------------------------------------------------------------ an older Pebble on the same file

    @Test
    fun reconcileRecordsWhatAnOlderPebbleChangedWithoutTheJournal() {
        val kept = notes.add("buy milk", at = 1_000)
        val gone = notes.add("call plumber", at = 1_000)
        val goneUid = noteUid(gone)
        // v0.1.2 knows nothing of the journal: it edits without a new HLC, deletes with DELETE, inserts with no uid.
        assertEquals(null, raw("UPDATE note SET text = 'buy oat milk', updated_at = 2000 WHERE id = $kept"))
        assertEquals(null, raw("DELETE FROM note WHERE id = $gone"))
        assertEquals(null, raw("INSERT INTO note(text, created_at, updated_at) VALUES ('from the old app', 3000, 3000)"))
        assertEquals(null, raw("INSERT INTO one_off_reminder(title, due_at, strictness) VALUES ('Old reminder', 500, 'NORMAL')"))
        assertTrue(journal.verify().isNotEmpty())

        notes.claim("dev1") // the start gives the new rows a uid first
        reminders.claim("dev1")
        assertEquals(Reconciled(backfilled = 2, repaired = 1, graves = 1), journal.reconcile(wall = 9_000))
        assertEquals(emptyList(), journal.verify())
        assertEquals("\"buy oat milk\"", entries("note", noteUid(kept))["text"])
        assertEquals(mapOf("deleted_at" to "9000"), entries("note", goneUid), "only the grave stays")
        assertEquals(Reconciled(0, 0, 0), journal.reconcile(wall = 10_000))
    }

    @Test
    fun thePurgeKeepsOnlyTheGraves() {
        val id = notes.add("secret", at = 1)
        val uid = noteUid(id)
        notes.delete(id, at = 2)
        val r = reminders.addOneOff("Call mom", dueAt = 5, at = 1)
        val rUid = oneOffUid(r)
        reminders.deleteOneOff(r, at = 2)
        calendar.save(CalendarEvent("e1", "Dentist", 10, 20, "Asia/Kolkata"), at = 1)
        calendar.delete("e1", at = 2)
        notes.add("stays", at = 1)
        assertEquals(1L, notes.purgeTombstones(before = 3))
        assertEquals(1L, reminders.purgeTombstones(before = 3))
        assertEquals(1L, calendar.purgeTombstones(before = 3))
        assertEquals(mapOf("deleted_at" to "2"), entries("note", uid), "the text of the deleted note left the journal")
        assertEquals(mapOf("deleted_at" to "2"), entries("one_off_reminder", rUid))
        assertEquals(mapOf("deleted_at" to "2"), entries("calendar_event", "e1"))
        assertEquals(emptyList(), journal.verify())
        assertEquals(false, calendar.save(CalendarEvent("e1", "Dentist", 10, 20, "Asia/Kolkata"), at = 4), "a grave keeps it deleted")
        assertEquals(Reconciled(0, 0, 0), journal.reconcile(wall = 5))
    }

    // ------------------------------------------------------------------ speed (spec section 9)

    @Test
    fun recordAddsLittleToAWrite() {
        repeat(200) { notes.add("warm up $it", at = it.toLong()) }
        val n = 1_000
        val withJournal = measureNanoTime { repeat(n) { notes.add("note $it", at = 10_000L + it) } } / n
        val plain = measureNanoTime {
            repeat(n) { db.transaction { db.wellnessQueries.insertNote("plain $it", 1, 1, null, null) } }
        } / n
        println("notes.add with the journal: ${withJournal / 1000} µs; a plain insert: ${plain / 1000} µs")
        assertNotNull(maxHlc())
        assertTrue(withJournal - plain < 5_000_000, "record adds ${(withJournal - plain) / 1000} µs") // spec: < 1 ms on the dev laptop
    }
}
