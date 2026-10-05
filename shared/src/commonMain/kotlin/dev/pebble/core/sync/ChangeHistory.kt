package dev.pebble.core.sync

import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One version of an item (WP E3c): the old values that one change replaced, or one edit of another device that lost
 * a merge ([lost]). [at] and [device]: the wall time and the device of the newest HLC of these old values.
 */
data class Version(
    val table: SyncTable,
    val uid: String,
    val fields: Map<String, JsonElement>,
    val at: Long,
    val device: String,
    val lost: Boolean,
)

/** A deleted item that can come back as a copy. [title]: the note text, or the reminder or event title. */
data class DeletedItem(val table: SyncTable, val uid: String, val title: String, val deletedAt: Long)

/**
 * The history of synced fields (WP E3c, ADR 0019, docs/specs/E3C-HISTORY.md). A trigger on `change_journal` keeps
 * each replaced value; the merge keeps lost edits ([ChangeJournal.apply]). History stays on this device.
 *
 * A restore is a new local edit with a new HLC; it syncs like any edit. A delete is final: a deleted item never
 * comes back in place, only as a copy with a new uid ([restoreCopy]).
 */
class ChangeHistory(private val db: PebbleDatabase, private val journal: ChangeJournal = ChangeJournal(db)) {
    private val q get() = db.changeHistoryQueries

    /** The versions of [uid], newest first. */
    fun versions(table: SyncTable, uid: String): List<Version> {
        val rows = q.historyOfRow(table.sqlName, uid).executeAsList().mapNotNull { r ->
            Hlc.parse(r.hlc)?.let { Entry(r.field_, Json.parseToJsonElement(r.value_), it, r.replaced_by, r.kind == LOST) }
        }
        val groups = rows.groupBy { if (it.lost) "lost ${it.hlc}" else "replaced ${it.replacedBy}" }
        return groups.values.map { group ->
            val newest = group.maxOf { it.hlc }
            Triple(
                Version(table, uid, group.associate { it.field to it.value }, newest.wallMillis, newest.device, group.first().lost),
                newest,
                group.first().replacedBy.orEmpty(),
            )
        }.sortedWith(compareByDescending<Triple<Version, Hlc, String>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    /**
     * Makes [version] the current one at [wall]: all its fields (also "done" and "archived"), never `deleted_at`,
     * as one new edit. Returns false, and changes nothing, if the item is deleted or gone. For a calendar event, the
     * caller then runs the agenda (`CalendarAgenda.eventChanged`), as for a local edit.
     */
    fun restore(version: Version, wall: Long): Boolean = journal.edit(version.table, version.uid, version.fields - "deleted_at", wall)

    /** Tombstones deleted in the last 90 days before [now], newest first. */
    fun recentlyDeleted(now: Long): List<DeletedItem> {
        val since = now - KEEP_DAYS * DAY_MILLIS
        val notes = q.deletedNotesSince(since).executeAsList().map { DeletedItem(SyncTable.NOTE, it.uid, it.text, it.deleted_at) }
        val reminders = q.deletedRemindersSince(since).executeAsList()
            .map { DeletedItem(SyncTable.ONE_OFF_REMINDER, it.uid, it.title, it.deleted_at) }
        val events = q.deletedEventsSince(since).executeAsList()
            .map { DeletedItem(SyncTable.CALENDAR_EVENT, it.uid, it.title, it.deleted_at) }
        return (notes + reminders + events).sortedByDescending { it.deletedAt }
    }

    /**
     * Makes a new item at [wall] with the fields of the deleted [item], through its repository, and returns the new
     * uid; null if the item is not a tombstone here (live, or purged). The deleted item stays deleted. A note comes
     * back not archived; a reminder comes back not done, with its old time. For an event, the caller then runs the
     * agenda for the new uid.
     */
    fun restoreCopy(item: DeletedItem, wall: Long): String? = db.transactionWithResult {
        val old = journal.fields(item.table, item.uid)?.takeIf { it["deleted_at"] != JsonNull }
            ?: return@transactionWithResult null
        when (item.table) {
            SyncTable.NOTE -> {
                val id = NoteRepository(db, journal).add(old.str("text"), wall)
                db.wellnessQueries.noteUid(id).executeAsOne().uid!!
            }

            SyncTable.ONE_OFF_REMINDER -> {
                val id = ReminderRepository(
                    db,
                    journal,
                ).addOneOff(old.str("title"), old.long("due_at"), Strictness.parse(old.str("strictness")), wall)
                db.remindersQueries.oneOffSyncInfo(id).executeAsOne().uid!!
            }

            SyncTable.CALENDAR_EVENT -> {
                val calendar = CalendarRepository(db, journal)
                val event = calendar.anyByUid(item.uid)!!.copy(uid = CalendarRepository.newUid())
                check(calendar.save(event, wall)) { "a new uid cannot be deleted" }
                event.uid
            }
        }
    }

    /** How many edits of other devices lost a merge since [time] (one for each item and change), for F4. */
    fun lostSince(time: Long): Int = q.lostSince(time).executeAsOne().toInt()

    /** "Clear history": deletes all history entries on this device. Returns how many. */
    fun clear(): Long = q.clearHistory().value

    /**
     * The daily purge: entries kept before [before], and all entries of rows that are gone (after the tombstone purge).
     * Returns how many.
     */
    fun purge(before: Long): Long = db.transactionWithResult { q.purgeHistoryBefore(before).value + q.purgeHistoryOfGoneRows().value }

    private class Entry(val field: String, val value: JsonElement, val hlc: Hlc, val replacedBy: String?, val lost: Boolean)

    private fun Map<String, JsonElement>.str(f: String): String = getValue(f).jsonPrimitive.content

    private fun Map<String, JsonElement>.long(f: String): Long = getValue(f).jsonPrimitive.long

    companion object {
        /** The same limit as for tombstones (ADR 0013): `PebbleApp.TOMBSTONE_DAYS`. */
        const val KEEP_DAYS = 90L
        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val LOST = "lost"
    }
}
