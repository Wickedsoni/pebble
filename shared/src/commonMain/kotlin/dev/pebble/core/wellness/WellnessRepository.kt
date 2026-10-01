package dev.pebble.core.wellness

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val GLASS_ML = 250

data class Note(val id: Long, val text: String, val updatedAt: Long)

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

class NoteRepository(private val db: PebbleDatabase) {
    private val q get() = db.wellnessQueries

    fun add(text: String, at: Long): Long = db.transactionWithResult {
        q.insertNote(text, at, at)
        q.lastInsertedId().executeAsOne()
    }

    fun update(id: Long, text: String, at: Long) = q.updateNote(text, at, id)

    fun archive(id: Long, at: Long) = q.archiveNote(at, id)

    fun activeFlow(limit: Long = 20): Flow<List<Note>> =
        q.activeNotes(limit).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { Note(it.id, it.text, it.updated_at) } }
}
