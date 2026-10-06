package dev.pebble.core

import dev.pebble.core.backup.Backup
import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.settings.DeviceIdentity
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.sync.ApplyResult
import dev.pebble.core.sync.ChangeBatch
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.Hlc
import dev.pebble.core.sync.RowChange
import dev.pebble.core.sync.Stamped
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The merge of the change journal (WP E3b, spec sections 7 and 8). */
class SyncMergeTest {
    /** One device: its own database, repositories, journal, agenda and clock. */
    class Replica(seed: Int, val db: PebbleDatabase = DatabaseFactory.inMemory()) {
        var now = 1_790_000_000_000L
        val device: String = DeviceIdentity.ensure(SettingsRepository(db), Random(seed))
        val journal = ChangeJournal(db)
        val notes = NoteRepository(db, journal)
        val reminders = ReminderRepository(db, journal)
        val calendar = CalendarRepository(db, journal)
        val agenda = CalendarAgenda(calendar, reminders) { ZoneId.of("Asia/Kolkata") }

        /** Pulls everything from [from] (in batches of [limit]) and runs the agenda for changed events, as the app will. */
        fun pullAll(from: Replica, limit: Int = 500): Int {
            var cursor: dev.pebble.core.sync.Cursor? = null
            var changed = 0
            while (true) {
                val batch = from.journal.changesSince(cursor, limit)
                if (batch.rows.isEmpty()) return changed
                val r = journal.apply(batch, now)
                assertIs<ApplyResult.Applied>(r, "$r")
                r.changedEvents.forEach { agenda.eventChanged(it, now) }
                changed += r.fieldsChanged
                cursor = batch.next
            }
        }

        /** The synced fields of all rows, live and tombstones, and the (table, uid, field, hlc, value) of the journal. */
        fun state(): Pair<Map<String, Any?>, Set<List<String>>> {
            val rows = buildMap<String, Any?> {
                db.wellnessQueries.syncNotes().executeAsList().forEach {
                    put("note ${it.uid}", listOf(it.text, it.created_at, it.archived, it.deleted_at))
                }
                db.remindersQueries.syncOneOffs().executeAsList()
                    .forEach { put("rem ${it.uid}", listOf(it.title, it.due_at, it.strictness, it.done_at, it.deleted_at)) }
                db.calendarQueries.syncEvents().executeAsList().forEach {
                    put(
                        "ev ${it.uid}",
                        listOf(
                            it.title, it.notes, it.start_at, it.end_at, it.all_day, it.tz, it.rrule, it.exdates,
                            it.remind_minutes, it.visibility, it.owner_device, it.deleted_at,
                        ),
                    )
                }
            }
            val journal = listOf("note", "one_off_reminder", "calendar_event").flatMap { t ->
                db.journalQueries.entriesOfTable(t).executeAsList().map { listOf(t, it.uid, it.field_, it.hlc, it.value_) }
            }.toSet()
            return rows to journal
        }
    }

    private fun event(uid: String, title: String = "Dentist", start: Long = 1_790_100_000_000L) =
        CalendarEvent(uid, title, start, start + 3_600_000, "Asia/Kolkata", remindMinutes = 15)

    @Test
    fun rowsAndEditsReachTheOtherDeviceAndBothAgree() {
        val a = Replica(1)
        val b = Replica(2)
        val noteId = a.notes.add("buy milk", a.now)
        a.reminders.addOneOff("Call mom", dueAt = a.now + 60_000, at = a.now)
        a.calendar.save(event("e1"), a.now)
        a.agenda.eventChanged("e1", a.now) // a linked reminder on A: local only
        assertTrue(b.pullAll(a) > 0)
        assertEquals(listOf("buy milk"), b.notes.recent().map { it.text })
        assertEquals(listOf("Call mom"), b.reminders.pendingOneOffs().filter { it.title == "Call mom" }.map { it.title })
        assertEquals(listOf("Dentist"), b.calendar.live().map { it.title })
        assertTrue(b.reminders.pendingOneOffs().any { it.title != "Call mom" }, "B's agenda made its own linked reminder")

        a.now += 60_000
        a.notes.update(noteId, "buy oat milk", a.now)
        b.pullAll(a)
        a.pullAll(b)
        assertEquals(listOf("buy oat milk"), b.notes.recent().map { it.text })
        assertEquals(a.state(), b.state())
        assertEquals(emptyList(), b.journal.verify())
    }

    @Test
    fun aDeleteWinsOverALaterEditInBothOrders() {
        for (deleteFirst in listOf(true, false)) {
            val a = Replica(1)
            val b = Replica(2)
            a.calendar.save(event("e1"), a.now)
            b.pullAll(a)
            a.now += 1_000
            a.calendar.delete("e1", a.now)
            b.now += 60_000 // B's edit is later, so it has the larger HLC
            b.calendar.save(event("e1", title = "Dentist (moved)"), b.now)
            if (deleteFirst) {
                b.pullAll(a)
                a.pullAll(b)
            } else {
                a.pullAll(b)
                b.pullAll(a)
            }
            assertTrue(a.calendar.live().isEmpty() && b.calendar.live().isEmpty(), "deleted on both ($deleteFirst)")
            assertEquals(a.state(), b.state(), "same tombstone on both ($deleteFirst)")
            assertFalse(b.calendar.save(event("e1"), b.now + 1), "and it does not come back")
        }
    }

    @Test
    fun theSameBatchTwiceChangesNothing() {
        val a = Replica(1)
        val b = Replica(2)
        a.notes.add("buy milk", a.now)
        a.calendar.save(event("e1"), a.now)
        val batch = a.journal.changesSince(null)
        val first = b.journal.apply(batch, b.now)
        assertEquals(ApplyResult.Applied(2, 16, listOf("e1")), first)
        val state = b.state()
        assertEquals(ApplyResult.Applied(0, 0, emptyList()), b.journal.apply(batch, b.now))
        assertEquals(state, b.state())
    }

    @Test
    fun aBadBatchIsRefusedAndChangesNothing() {
        val a = Replica(1)
        val b = Replica(2)
        a.notes.add("buy milk", a.now)
        b.notes.add("on B", b.now)
        val good = a.journal.changesSince(null)
        val row = good.rows.single()
        fun with(change: (RowChange) -> RowChange) = good.copy(rows = listOf(change(row)))
        fun field(name: String, s: Stamped) = with { it.copy(fields = it.fields + (name to s)) }
        val hlc = row.fields.getValue("text").hlc
        val ahead = Hlc(b.now + 61 * 60_000, 0, a.device).toString()
        val cases = mapOf(
            "unknown table" to with { it.copy(table = "memory") },
            "not synced" to field("origin_device", Stamped(JsonPrimitive("x"), hlc)),
            "bad HLC" to field("text", Stamped(JsonPrimitive("x"), "yesterday")),
            "bad value" to field("created_at", Stamped(JsonPrimitive("not a number"), hlc)),
            "bad value (flag)" to field("archived", Stamped(JsonPrimitive(7), hlc)),
            "bad value (null)" to field("text", Stamped(JsonNull, hlc)),
            "clock is ahead" to field("text", Stamped(JsonPrimitive("x"), ahead)),
            "lacks" to with { it.copy(fields = it.fields - "created_at") },
            "bad uid" to with { it.copy(uid = "") },
        )
        val before = b.state()
        for ((reason, batch) in cases) {
            val r = b.journal.apply(batch, b.now)
            assertIs<ApplyResult.Refused>(r, reason)
            assertTrue(r.reason.contains(reason.substringBefore(" (")), "$reason -> ${r.reason}")
            assertEquals(before, b.state(), "nothing changed after: $reason")
        }
        val event = ChangeBatch(
            good.epoch,
            listOf(RowChange("calendar_event", "e9", mapOf("visibility" to Stamped(JsonPrimitive("public"), hlc)))),
            good.next,
        )
        assertTrue((b.journal.apply(event, b.now) as ApplyResult.Refused).reason.contains("bad value for calendar_event.visibility"))
    }

    @Test
    fun aRowThatThisDeviceRemovedStaysRemoved() {
        val a = Replica(1)
        val b = Replica(2)
        val id = a.notes.add("secret", a.now)
        b.pullAll(a)
        val bId = b.db.wellnessQueries.activeNotes(10).executeAsList().single().id
        b.notes.delete(bId, b.now)
        b.notes.purgeTombstones(before = b.now + 1) // only the grave stays on B
        a.now += 1_000
        a.notes.update(id, "secret, edited", a.now)
        val r = b.journal.apply(a.journal.changesSince(null), b.now)
        assertEquals(ApplyResult.Applied(0, 0, emptyList(), dropped = 1), r)
        assertTrue(b.notes.recent().isEmpty())
    }

    @Test
    fun aGraveOfAPurgedRowSyncsToANewDeviceAndKeepsLaterEntriesOut() {
        val a = Replica(1)
        val id = a.notes.add("gone", a.now)
        a.notes.add("stays", a.now + 1)
        a.notes.delete(id, a.now + 2)
        a.notes.purgeTombstones(before = a.now + 3) // only the grave of "gone" stays on A
        val graveOnly = a.journal.changesSince(null).rows.single { it.fields.keys == setOf("deleted_at") }

        val b = Replica(2)
        assertTrue(b.pullAll(a) > 0) // the whole batch is applied, not refused
        assertEquals(listOf("stays"), b.notes.recent().map { it.text }, "B never shows the purged note")
        assertTrue(b.journal.isDeleted(dev.pebble.core.sync.SyncTable.NOTE, graveOnly.uid), "B has the grave")
        assertEquals(emptyList(), b.journal.verify())

        // A later live entry of the same uid (a device that was offline) is dropped on B.
        val live = RowChange(
            "note",
            graveOnly.uid,
            mapOf(
                "text" to Stamped(JsonPrimitive("gone, edited"), Hlc(a.now + 9_000, 0, a.device).toString()),
                "created_at" to Stamped(JsonPrimitive(1), Hlc(a.now + 9_000, 0, a.device).toString()),
                "archived" to Stamped(JsonPrimitive(0), Hlc(a.now + 9_000, 0, a.device).toString()),
                "deleted_at" to Stamped(JsonNull, Hlc(a.now + 9_000, 0, a.device).toString()),
            ),
        )
        val r = b.journal.apply(ChangeBatch(a.journal.epoch(), listOf(live), a.journal.changesSince(null).next), b.now)
        assertEquals(ApplyResult.Applied(0, 0, emptyList(), dropped = 1), r)
        assertEquals(listOf("stays"), b.notes.recent().map { it.text })
    }

    @Test
    fun aNewRowThatIsLiveAndIncompleteIsStillRefused() {
        val a = Replica(1)
        val b = Replica(2)
        a.notes.add("x", a.now)
        val row = a.journal.changesSince(null).rows.single()
        val cut = RowChange(row.table, row.uid, row.fields - "archived")
        assertTrue(
            b.journal.apply(ChangeBatch(a.journal.epoch(), listOf(cut), a.journal.changesSince(null).next), b.now) is ApplyResult.Refused,
        )
    }

    @Test
    fun calendarMadeRemindersAreNeverInABatch() {
        val a = Replica(1)
        a.calendar.save(event("e1"), a.now)
        assertEquals(1, a.agenda.eventChanged("e1", a.now))
        val batch = a.journal.changesSince(null)
        assertEquals(listOf("calendar_event"), batch.rows.map { it.table })
    }

    @Test
    fun aRestoreGivesANewEpochSoPeersReadFromTheStart() {
        val dir = Files.createTempDirectory("pebble-epoch").toFile().apply { deleteOnExit() }
        val file = File(dir, Backup.DB)
        val a = Replica(1, DatabaseFactory.create(file))
        a.notes.add("first", a.now)
        val backup = File(dir, "a.pebblebackup").toPath()
        Backup.export(file, backup, "a long passphrase".toCharArray(), iterations = 1_000)
        a.notes.add("second", a.now + 1)
        val seen = a.journal.changesSince(null) // a peer read everything, up to "second"
        assertEquals(2, seen.rows.size)

        Backup.stageRestore(backup, dir, "a long passphrase".toCharArray())
        assertTrue(Backup.applyStaged(dir)!!.startsWith("restored"))
        val restored = ChangeJournal(DatabaseFactory.create(file))
        assertNotEquals(seen.epoch, restored.epoch())
        val again = restored.changesSince(seen.next)
        assertEquals(restored.epoch(), again.epoch)
        assertEquals(1, again.rows.size, "an old cursor of another epoch reads from the start")
    }

    @Test
    fun changesSinceAndApplyAreFastEnough() {
        val a = Replica(1)
        a.db.transaction { repeat(500) { a.notes.add("note $it", a.now + it) } }
        repeat(3) { a.journal.changesSince(null, 2_000) } // warm up; the limit counts entries (4 for each note)
        var batch: ChangeBatch? = null
        val read = measureNanoTime { batch = a.journal.changesSince(null, 2_000) } / 1_000_000.0
        repeat(3) { Replica(10 + it).journal.apply(batch!!, a.now) } // warm up the merge too
        val b = Replica(2)
        val write =
            measureNanoTime { assertEquals(500, (b.journal.apply(batch!!, b.now) as ApplyResult.Applied).rowsChanged) } / 1_000_000.0
        println("changesSince of 500 rows: $read ms; apply of 500 rows: $write ms")
        assertTrue(read < 250 && write < 1_000, "spec: < 50 ms and < 200 ms on the dev laptop; read $read, apply $write")
    }
}
