package dev.pebble.core.memory

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class MemoryKind(val label: String) {
    HABIT("Habits & timing"),
    FACT("Things you told me"),
    STREAK("Streaks & progress"),
    MOOD("Mood"),
    MEDIA("What you watch"),
}

data class Memory(
    val id: Long,
    val kind: MemoryKind,
    val key: String,
    val text: String,
    val data: String?,
    val fromUser: Boolean,
    val updatedAt: Long,
)

data class MoodEntry(val atMillis: Long, val score: Int)

data class MediaEntry(val atMillis: Long, val app: String, val title: String)

/** Storage for everything Pebble knows about you. Local only. */
class MemoryRepository(private val db: PebbleDatabase) {
    private val q get() = db.memoryQueries

    private fun map(
        id: Long,
        kind: String,
        key: String,
        text: String,
        data: String?,
        source: String,
        @Suppress("UNUSED_PARAMETER") hidden: Long,
        @Suppress("UNUSED_PARAMETER") created: Long,
        updated: Long,
    ) = Memory(id, MemoryKind.entries.firstOrNull { it.name == kind } ?: MemoryKind.FACT, key, text, data, source == "user", updated)

    fun visible(): List<Memory> = q.visibleMemories(::map).executeAsList()

    fun visibleFlow(): Flow<List<Memory>> = q.visibleMemories(::map).asFlow().mapToList(Dispatchers.Default)

    fun byKey(key: String): Memory? = q.memoryByKey(key, ::map).executeAsOneOrNull()

    /** Learned memory: created or refreshed in place, unless you've deleted it. */
    fun putDerived(kind: MemoryKind, key: String, text: String, data: String? = null, at: Long) {
        q.upsertDerived(kind.name, key, text, data, at, at)
    }

    /** Drops a learned memory that no longer holds (e.g. a streak that broke). Respects deletions. */
    fun dropDerived(key: String) {
        q.removeDerived(key)
    }

    fun remember(text: String, at: Long): String {
        val key = "fact.$at"
        q.insertFact(key, text, at, at)
        return key
    }

    /** "Forget this": user facts are deleted, learned ones are hidden so they don't come back. */
    fun forget(memory: Memory) {
        if (memory.fromUser) q.deleteUserMemory(memory.id) else q.hideMemory(memory.id)
    }

    fun logMood(score: Int, at: Long) = q.insertMood(at, score.toLong())

    fun moodSince(since: Long): List<MoodEntry> = q.moodSince(since).executeAsList().map { MoodEntry(it.at_millis, it.score.toInt()) }

    fun logMedia(app: String, title: String, at: Long) = q.insertMedia(at, app, title)

    fun lastMedia(): MediaEntry? = q.lastMedia().executeAsOneOrNull()?.let { MediaEntry(it.at_millis, it.app, it.title) }

    fun mediaSince(since: Long): List<MediaEntry> = q.mediaSince(since).executeAsList().map { MediaEntry(it.at_millis, it.app, it.title) }

    fun clearMedia() = q.clearMedia()
}
