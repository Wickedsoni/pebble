package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.settings.DeviceIdentity
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.wellness.NoteRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** WP E1: deletes leave tombstones that reads skip; new rows get a uid and this device's id; old tombstones go. */
class SyncReadyRowsTest {
    private val db = DatabaseFactory.inMemory()
    private val settings = SettingsRepository(db)
    private val notes = NoteRepository(db)
    private val reminders = ReminderRepository(db)

    @Test
    fun theDeviceIdIsMadeOnceAndIsBase32() {
        val id = DeviceIdentity.ensure(settings, Random(1))
        assertTrue(id.matches(Regex("[a-z2-7]{26}")), id)
        assertEquals(id, DeviceIdentity.ensure(settings, Random(2)), "made once, never changed")
        assertNotEquals(DeviceIdentity.newId(Random(3)), DeviceIdentity.newId(Random(4)))
    }

    @Test
    fun aDeletedNoteIsATombstoneThatReadsSkip() {
        val keep = notes.add("buy milk", 1)
        val gone = notes.add("call plumber", 2)
        notes.delete(gone, at = 10)
        assertEquals(listOf(keep), notes.recent(10).map { it.id })
        assertEquals(listOf("buy milk"), db.vectorsQueries.sourceNotes().executeAsList().map { it.text }, "search forgets it too")
        assertEquals(0L, notes.purgeTombstones(before = 10), "not older than the limit yet")
        assertEquals(1L, notes.purgeTombstones(before = 11))
    }

    @Test
    fun aDeletedReminderLeavesThePendingList() {
        val id = reminders.addOneOff("Call mom", dueAt = 5_000, at = 1)
        reminders.addOneOff("Pay rent", dueAt = 6_000, at = 1)
        reminders.deleteOneOff(id, at = 20)
        assertEquals(listOf("Pay rent"), reminders.pendingOneOffs().map { it.title })
        assertEquals(1L, reminders.purgeTombstones(before = 21))
        assertEquals(0L, reminders.purgeTombstones(before = 21))
    }

    @Test
    fun newRowsGetAUidAndThisDevicesId() {
        notes.add("before the id", 1)
        val device = DeviceIdentity.ensure(settings, Random(7))
        notes.claim(device)
        reminders.claim(device)
        notes.add("after the id", 2)
        reminders.addOneOff("Call mom", dueAt = 5_000, at = 3)
        val n = db.wellnessQueries.activeNotes(10).executeAsList()
        assertEquals(setOf(device), n.map { it.origin_device }.toSet(), "claimed, then recorded on insert")
        assertEquals(2, n.mapNotNull { it.uid }.toSet().size)
        val r = db.remindersQueries.pendingOneOffs().executeAsList().single()
        assertEquals(device, r.origin_device)
        assertEquals(3L, r.updated_at)
        assertTrue(r.uid!!.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun rowsFromAnOlderPebbleGetAUidAtTheNextStart() {
        // An older Pebble (v0.1.2) can still write to the same file: its inserts have no uid and no device.
        val file = java.nio.file.Files.createTempFile("pebble-old-app", ".db").toFile().apply { deleteOnExit() }
        val fileDb = DatabaseFactory.create(file)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("INSERT INTO note(text, created_at, updated_at) VALUES ('from the old app', 1, 1)")
                s.execute("INSERT INTO one_off_reminder(title, due_at, strictness) VALUES ('Old app reminder', 500, 'NORMAL')")
            }
        }
        NoteRepository(fileDb).claim("dev1")
        ReminderRepository(fileDb).claim("dev1")
        val note = fileDb.wellnessQueries.activeNotes(10).executeAsList().single()
        assertTrue(note.uid!!.matches(Regex("[0-9a-f]{32}")) && note.origin_device == "dev1")
        val rem = fileDb.remindersQueries.pendingOneOffs().executeAsList().single()
        assertTrue(rem.uid!!.matches(Regex("[0-9a-f]{32}")) && rem.origin_device == "dev1")
        assertEquals(500L, rem.updated_at, "a missing change time falls back to the due time")
    }

    @Test
    fun doneAndRescheduleRecordTheChangeTime() {
        val id = reminders.addOneOff("Call mom", dueAt = 5_000, at = 1)
        reminders.rescheduleOneOff(id, dueAt = 9_000, at = 4)
        assertEquals(4L, db.remindersQueries.pendingOneOffs().executeAsList().single().updated_at)
        reminders.markOneOffDone(id, at = 8)
        assertTrue(reminders.pendingOneOffs().isEmpty())
    }
}
