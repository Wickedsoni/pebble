package dev.pebble.core

import dev.pebble.core.SyncMergeTest.Replica
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.sync.ChangeHistory
import dev.pebble.core.sync.Hlc
import dev.pebble.core.sync.SyncTable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** History and restore (WP E3c-1, docs/specs/E3C-HISTORY.md section 7). */
class ChangeHistoryTest {
    private val Replica.history get() = ChangeHistory(db, journal)

    private fun Replica.noteUid(id: Long): String = db.wellnessQueries.noteUid(id).executeAsOne().uid!!

    /** All history rows as (table, uid, field, value, kind); graves keep the uids of purged rows in the journal. */
    private fun Replica.rawHistory(): List<List<String?>> = buildList {
        for (t in SyncTable.entries) {
            db.journalQueries.entriesOfTable(t.sqlName).executeAsList().map { it.uid }.toSet().forEach { uid ->
                db.changeHistoryQueries.historyOfRow(t.sqlName, uid).executeAsList()
                    .forEach { add(listOf(t.sqlName, uid, it.field_, it.value_, it.kind)) }
            }
        }
    }

    private fun event(uid: String, title: String, start: Long = 1_790_100_000_000L) =
        CalendarEvent(uid = uid, title = title, startAt = start, endAt = start + 3_600_000, tz = "Asia/Kolkata")

    // ------------------------------------------------------------------ the trigger (spec 4.1)

    @Test
    fun aLocalEditKeepsTheReplacedValue() {
        val a = Replica(1)
        val id = a.notes.add("buy milk", a.now)
        a.notes.update(id, "buy oat milk", a.now + 1)
        val v = a.history.versions(SyncTable.NOTE, a.noteUid(id))
        assertEquals(1, v.size)
        assertEquals(mapOf("text" to JsonPrimitive("buy milk")), v.single().fields)
        assertFalse(v.single().lost)
        assertEquals(a.device, v.single().device)
        assertEquals(a.now, v.single().at)
    }

    @Test
    fun insertOrReplaceFiresTheTriggerOnceForEachHlc() {
        val a = Replica(1)
        val id = a.notes.add("a", a.now)
        val uid = a.noteUid(id)
        a.notes.update(id, "b", a.now + 1)
        val hlc = a.db.journalQueries.entriesOfRow("note", uid).executeAsList().first { it.field_ == "text" }.hlc
        // The same entry again (the same HLC), as a batch applied two times writes it: nothing more is kept.
        a.db.journalQueries.recordEntry("note", uid, "text", "\"b\"", hlc)
        assertEquals(listOf(listOf("note", uid, "text", "\"a\"", "replaced")), a.rawHistory())
    }

    @Test
    fun deletedAtIsNeverKept() {
        val a = Replica(1)
        val id = a.notes.add("a", a.now)
        a.notes.delete(id, a.now + 1)
        assertEquals(emptyList(), a.rawHistory(), "deleted_at went from null to a time; a delete is final")
    }

    @Test
    fun aMergeWinnerKeepsTheReplacedValue() {
        val a = Replica(1)
        val b = Replica(2)
        val id = a.notes.add("draft", a.now)
        b.pullAll(a)
        a.notes.update(id, "final", a.now + 10)
        b.now += 20
        b.pullAll(a)
        val v = b.history.versions(SyncTable.NOTE, a.noteUid(id))
        assertEquals(listOf(mapOf("text" to JsonPrimitive("draft"))), v.map { it.fields })
        assertFalse(v.single().lost)
        assertEquals(a.device, v.single().device, "A made the old value")
    }

    @Test
    fun aReconcileRepairKeepsTheReplacedValue() {
        val a = Replica(1)
        val id = a.notes.add("before", a.now)
        val uid = a.noteUid(id)
        // An older Pebble changes the text and keeps the HLC: the guard triggers let it pass.
        a.db.wellnessQueries.updateNote(text = "by old app", at = a.now + 1, hlc = null, id = id)
        assertEquals(1, a.journal.reconcile(a.now + 2).repaired)
        assertEquals(listOf(mapOf("text" to JsonPrimitive("before"))), a.history.versions(SyncTable.NOTE, uid).map { it.fields })
    }

    // ------------------------------------------------------------------ lost edits (spec 4.2)

    @Test
    fun theNewerEditKeepsTheOtherAsLostAndTheOlderKeepsItsOwnAsReplaced() {
        val a = Replica(1)
        val b = Replica(2)
        val id = a.notes.add("shared", a.now)
        val uid = a.noteUid(id)
        b.pullAll(a)
        a.notes.update(id, "from A", a.now + 10)
        b.notes.update(b.notes.recent().single().id, "from B", b.now + 20) // newer
        b.pullAll(a)
        a.now += 30
        a.pullAll(b)

        val onB = b.history.versions(SyncTable.NOTE, uid)
        assertEquals(listOf(true, false), onB.map { it.lost }, "newest first: A's lost edit (t+10), then B's replaced 'shared'")
        assertEquals(JsonPrimitive("from A"), onB.first().fields["text"])
        assertEquals(a.device, onB.first().device)
        assertEquals(1, b.history.lostSince(0))

        val onA = a.history.versions(SyncTable.NOTE, uid)
        assertEquals(listOf("from A", "shared"), onA.map { it.fields.getValue("text").let { j -> (j as JsonPrimitive).content } })
        assertTrue(onA.none { it.lost }, "A's own edit lost, so A keeps it as replaced")
        assertEquals(0, a.history.lostSince(0))
    }

    @Test
    fun aLostEditWithTheSameValueIsNotKept() {
        val a = Replica(1)
        val b = Replica(2)
        val id = a.notes.add("shared", a.now)
        val uid = a.noteUid(id)
        b.pullAll(a)
        a.notes.update(id, "same", a.now + 10)
        b.notes.update(b.notes.recent().single().id, "same", b.now + 20)
        b.pullAll(a)
        assertEquals(0, b.history.lostSince(0))
    }

    // ------------------------------------------------------------------ versions (spec 5)

    @Test
    fun versionsGroupTheFieldsOfOneChangeNewestFirst() {
        val a = Replica(1)
        a.calendar.save(event("e1", "Dentist"), a.now)
        a.calendar.save(event("e1", "Dentist (moved)", start = 1_790_200_000_000L), a.now + 10) // title + start + end
        a.calendar.save(event("e1", "Dentist at 5", start = 1_790_200_000_000L), a.now + 20) // title only
        val v = a.history.versions(SyncTable.CALENDAR_EVENT, "e1")
        assertEquals(2, v.size)
        assertEquals(mapOf("title" to JsonPrimitive("Dentist (moved)")), v[0].fields)
        assertEquals(setOf("title", "start_at", "end_at"), v[1].fields.keys)
        assertEquals(JsonPrimitive("Dentist"), v[1].fields["title"])
    }

    // ------------------------------------------------------------------ restore (spec 5)

    @Test
    fun aRestoreWritesOnlyTheFieldsThatDifferWithANewHlcAndSyncs() {
        val a = Replica(1)
        val b = Replica(2)
        a.calendar.save(event("e1", "Dentist"), a.now)
        a.calendar.save(event("e1", "Dentist (moved)", start = 1_790_200_000_000L), a.now + 10)
        a.calendar.save(event("e1", "Dentist", start = 1_790_200_000_000L), a.now + 20) // title back by hand
        b.pullAll(a)
        val before = a.db.journalQueries.entriesOfRow("calendar_event", "e1").executeAsList().associate { it.field_ to it.hlc }

        val first = a.history.versions(SyncTable.CALENDAR_EVENT, "e1").last() // "Dentist" at the first start
        assertTrue(a.history.restore(first, a.now + 30))

        assertEquals(1_790_100_000_000L, a.calendar.byUid("e1")!!.startAt)
        val after = a.db.journalQueries.entriesOfRow("calendar_event", "e1").executeAsList().associate { it.field_ to it.hlc }
        val changed = after.filter { (f, h) -> before[f] != h }.keys
        assertEquals(setOf("start_at", "end_at"), changed, "the title is the same, so it is not written")
        assertEquals(a.now + 30, Hlc.parse(after.getValue("start_at"))!!.wallMillis)
        assertEquals(emptyList(), a.journal.verify())

        b.now += 40
        b.pullAll(a)
        assertEquals(1_790_100_000_000L, b.calendar.byUid("e1")!!.startAt, "B gets the restore as an edit")
        assertEquals(a.state(), b.state())
    }

    @Test
    fun aRestoreBringsBackDone() {
        val a = Replica(1)
        val id = a.reminders.addOneOff("Call mom", a.now + 3_600_000, at = a.now)
        a.reminders.markOneOffDone(id, a.now + 10)
        val uid = a.db.remindersQueries.oneOffSyncInfo(id).executeAsOne().uid!!
        val v = a.history.versions(SyncTable.ONE_OFF_REMINDER, uid).single()
        assertTrue(a.history.restore(v, a.now + 20))
        assertEquals(listOf("Call mom"), a.reminders.pendingOneOffs().map { it.title })
    }

    @Test
    fun aRestoreOfADeletedItemReturnsFalse() {
        val a = Replica(1)
        val id = a.notes.add("a", a.now)
        a.notes.update(id, "b", a.now + 1)
        val uid = a.noteUid(id)
        val v = a.history.versions(SyncTable.NOTE, uid).single()
        a.notes.delete(id, a.now + 2)
        assertFalse(a.history.restore(v, a.now + 3))
        assertNull(a.notes.recent().firstOrNull())
    }

    // ------------------------------------------------------------------ restore as a copy (spec 5)

    @Test
    fun restoreAsACopyMakesANewItemForEachTableAndTheOldOneStaysDeleted() {
        val a = Replica(1)
        val b = Replica(2)
        val noteId = a.notes.add("passport number", a.now)
        val remId = a.reminders.addOneOff("Pay rent", a.now + 3_600_000, at = a.now)
        a.calendar.save(event("e1", "Dentist"), a.now)
        a.notes.delete(noteId, a.now + 1)
        a.reminders.deleteOneOff(remId, a.now + 2)
        a.calendar.delete("e1", a.now + 3)
        b.pullAll(a)

        val deleted = a.history.recentlyDeleted(a.now + 10)
        assertEquals(listOf("Dentist", "Pay rent", "passport number"), deleted.map { it.title }, "newest first")
        val copies = deleted.map { assertNotNull(a.history.restoreCopy(it, a.now + 10)) }
        deleted.zip(copies).forEach { (d, c) ->
            assertNotEquals(d.uid, c)
            assertTrue(a.journal.isDeleted(d.table, d.uid))
        }
        assertEquals(listOf("passport number"), a.notes.recent().map { it.text })
        assertEquals(listOf("Pay rent"), a.reminders.pendingOneOffs().map { it.title })
        assertEquals(listOf("Dentist"), a.calendar.live().map { it.title })
        assertEquals(emptyList(), a.journal.verify())

        b.now += 20
        b.pullAll(a)
        assertEquals(a.state(), b.state())
        deleted.forEach { assertTrue(b.journal.isDeleted(it.table, it.uid), "${it.title} stays deleted on B") }
        assertNull(a.history.restoreCopy(deleted.first().copy(uid = copies.first()), a.now + 30), "a live item is not a tombstone")
    }

    @Test
    fun recentlyDeletedSkipsItemsOlderThan90Days() {
        val a = Replica(1)
        val id = a.notes.add("old", a.now)
        a.notes.delete(id, a.now)
        assertEquals(1, a.history.recentlyDeleted(a.now + 89L * 86_400_000).size)
        assertEquals(0, a.history.recentlyDeleted(a.now + 91L * 86_400_000).size)
    }

    // ------------------------------------------------------------------ purge and clear (spec 4.3)

    @Test
    fun purgeRemovesOldEntriesAndEntriesOfPurgedRowsAndClearRemovesAll() {
        val a = Replica(1)
        val keep = a.notes.add("a", a.now)
        a.notes.update(keep, "b", a.now + 1)
        val gone = a.notes.add("secret", a.now)
        a.notes.update(gone, "secret 2", a.now + 1)
        a.notes.delete(gone, a.now + 2)
        assertEquals(2, a.rawHistory().size)
        assertEquals(0L, a.history.purge(before = 0), "nothing is older; both rows are still here")

        a.notes.purgeTombstones(before = a.now + 3)
        assertEquals(1L, a.history.purge(before = 0), "the history of the purged note goes with it")
        assertEquals(listOf("\"a\""), a.rawHistory().map { it[3] })

        assertEquals(1L, a.history.purge(before = Long.MAX_VALUE), "kept_at older than the limit")
        a.notes.update(keep, "c", a.now + 4)
        assertEquals(1L, a.history.clear())
        assertEquals(emptyList(), a.rawHistory())
    }

    @Test
    fun clearOfOneItemRemovesOnlyItsHistoryAndKeepsTheItem() {
        val a = Replica(1)
        val secret = a.notes.add("pin 4821", a.now)
        a.notes.update(secret, "pin: ask me", a.now + 1)
        val other = a.notes.add("a", a.now)
        a.notes.update(other, "b", a.now + 1)
        assertEquals(1L, a.history.clear(SyncTable.NOTE, a.noteUid(secret)))
        assertEquals(emptyList(), a.history.versions(SyncTable.NOTE, a.noteUid(secret)))
        assertEquals(1, a.history.versions(SyncTable.NOTE, a.noteUid(other)).size, "other items keep their history")
        assertEquals(setOf("pin: ask me", "b"), a.notes.recent().map { it.text }.toSet())
        assertEquals(emptyList(), a.journal.verify())
    }

    // ------------------------------------------------------------------ sync never sends history (spec 2)

    @Test
    fun aBatchHasOnlyTheCurrentValues() {
        val a = Replica(1)
        val id = a.notes.add("old text", a.now)
        a.notes.update(id, "new text", a.now + 1)
        val batch = a.journal.changesSince(null)
        val texts = batch.rows.flatMap { r -> r.fields.values.map { it.value } }
        assertTrue(JsonPrimitive("old text") !in texts)
        assertTrue(JsonPrimitive("new text") in texts)
    }
}
