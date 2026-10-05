package dev.pebble.core.wellness

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.ChangeJournal.Companion.v
import dev.pebble.core.sync.SyncTable
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.CoroutineContext

const val GLASS_ML = 250

/** [uid]: its sync uid, for its history (WP E3c); null for a note of an older Pebble that has no uid yet. */
data class Note(val id: Long, val text: String, val updatedAt: Long, val uid: String? = null)

class WaterRepository(private val db: PebbleDatabase) {
    private val q get() = db.wellnessQueries

    fun log(ml: Int = GLASS_ML, at: Long) = q.logWater(at, ml.toLong())

    fun undoLast(since: Long) = q.undoLastWaterSince(since)

    fun totalSince(since: Long): Int = q.waterSince(since).executeAsOne().toInt()

    /** Raw (time, ml) entries since [since], oldest first — for charts and streaks. */
    fun entriesSince(since: Long): List<Pair<Long, Int>> =
        q.waterLogSince(since).executeAsList().map { it.at_millis to it.ml.toInt() }

    fun totalSinceFlow(since: Long): Flow<Int> =
        q.waterSince(since).asFlow().mapToOne(Dispatchers.Default).map { it.toInt() }
}

/** Notes. Each write to a synced field goes into the change journal first, with one HLC (WP E3, [ChangeJournal]). */
class NoteRepository(private val db: PebbleDatabase, private val journal: ChangeJournal = ChangeJournal(db)) {
    private val q get() = db.wellnessQueries

    fun add(text: String, at: Long): Long = db.transactionWithResult {
        val uid = ChangeJournal.newUid()
        val hlc = journal.record(
            SyncTable.NOTE,
            uid,
            mapOf("text" to v(text), "created_at" to v(at), "archived" to v(0L), "deleted_at" to JsonNull),
            at,
        )
        q.insertNote(text = text, createdAt = at, updatedAt = at, uid = uid, hlc = hlc.toString())
        q.lastInsertedId().executeAsOne()
    }

    fun update(id: Long, text: String, at: Long) = db.transaction {
        q.updateNote(text = text, at = at, hlc = record(id, mapOf("text" to v(text)), at), id = id)
    }

    fun archive(id: Long, at: Long) = db.transaction {
        q.archiveNote(at = at, hlc = record(id, mapOf("archived" to v(1L)), at), id = id)
    }

    /**
     * Removes a note from view (used to undo a note Pebble created by mistake). The row stays as a tombstone
     * (`deleted_at`) so that sync can tell other devices (WP E1); [purgeTombstones] removes it later.
     */
    fun delete(id: Long, at: Long? = null) = db.transaction {
        val time = at ?: journal.now()
        q.deleteNote(at = time, hlc = record(id, mapOf("deleted_at" to v(time)), time), id = id)
    }

    /** Removes tombstones older than [before], and their journal entries except the graves. Returns how many. */
    fun purgeTombstones(before: Long): Long = db.transactionWithResult {
        q.purgeNoteTombstones(before).value.also { journal.purgeEntries(SyncTable.NOTE) }
    }

    /** The HLC of the journal entries for [values] of note [id], or null if the note has no uid yet (reconcile records it). */
    private fun record(id: Long, values: Map<String, JsonElement>, at: Long): String? =
        q.noteUid(id).executeAsOneOrNull()?.uid?.let { journal.record(SyncTable.NOTE, it, values, at).toString() }

    /** Gives notes made before this device had an id, or by an older Pebble without uids, a uid and [deviceId]. */
    fun claim(deviceId: String) = db.transaction {
        q.fillNoteUids()
        q.claimNotes(deviceId)
    }

    fun recent(limit: Long = 5): List<Note> =
        q.activeNotes(limit).executeAsList().map { Note(it.id, it.text, it.updated_at, it.uid) }

    fun activeFlow(limit: Long = 20, context: CoroutineContext = Dispatchers.Default): Flow<List<Note>> =
        q.activeNotes(limit).asFlow().mapToList(context).map { rows -> rows.map { Note(it.id, it.text, it.updated_at, it.uid) } }
}
