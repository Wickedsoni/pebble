package dev.pebble.core.reminders

import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.ChangeJournal.Companion.v
import dev.pebble.core.sync.SyncTable
import dev.pebble.db.One_off_reminder
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.CoroutineContext

/** [uid]: its sync uid, for its history (WP E3c); null for a reminder that an event made (not synced, no history). */
data class OneOffReminder(val id: Long, val title: String, val dueAt: Long, val strictness: Strictness, val uid: String? = null)

/**
 * Repeating rules and one-off reminders. Each write to a synced field of a one-off reminder goes into the change
 * journal first, with one HLC (WP E3, [ChangeJournal]). Reminders that an event made are not synced.
 */
class ReminderRepository(private val db: PebbleDatabase, private val journal: ChangeJournal = ChangeJournal(db)) {
    private val q get() = db.remindersQueries

    fun seedDefaults() = db.transaction {
        DefaultRules.forEach {
            q.insertRuleIfMissing(it.id, it.kind.name, it.title, it.intervalMinutes.toLong(), it.strictness.name, 1L)
        }
    }

    fun rules(): List<ReminderRule> = q.allRules().executeAsList().map {
        ReminderRule(
            id = it.id,
            kind = ReminderKind.parse(it.kind),
            title = it.title,
            intervalMinutes = it.interval_minutes.toInt(),
            strictness = Strictness.parse(it.strictness),
            enabled = it.enabled != 0L,
            activeFromMinute = it.active_from_minute.toInt(),
            activeToMinute = it.active_to_minute.toInt(),
            lastDoneAt = it.last_done_at,
        )
    }

    fun updateRule(id: String, intervalMinutes: Int, strictness: Strictness, enabled: Boolean) =
        q.updateRuleSettings(intervalMinutes.toLong(), strictness.name, if (enabled) 1L else 0L, id)

    fun markRuleDone(id: String, at: Long) = q.markRuleDone(at, id)

    fun pendingOneOffs(): List<OneOffReminder> = q.pendingOneOffs().executeAsList().map(::toOneOff)

    /** Returns the new reminder's id. [at]: when it was made (null: now). */
    fun addOneOff(
        title: String,
        dueAt: Long,
        strictness: Strictness = Strictness.NORMAL,
        at: Long? = null,
    ): Long = db.transactionWithResult {
        val time = at ?: journal.now()
        val uid = ChangeJournal.newUid()
        val hlc = journal.record(
            this,
            SyncTable.ONE_OFF_REMINDER,
            uid,
            mapOf(
                "title" to v(title),
                "due_at" to v(dueAt),
                "strictness" to v(strictness.name),
                "done_at" to JsonNull,
                "deleted_at" to JsonNull,
            ),
            time,
        )
        q.insertOneOff(title = title, dueAt = dueAt, strictness = strictness.name, uid = uid, at = time, hlc = hlc.toString())
        q.lastOneOffId().executeAsOne()
    }

    /**
     * Removes a reminder from view (used to undo one Pebble created by mistake). The row stays as a tombstone
     * (`deleted_at`) so that sync can tell other devices (WP E1); [purgeTombstones] removes it later. A reminder that
     * is deleted already keeps its first `deleted_at`: returns false and writes nothing.
     */
    fun deleteOneOff(id: Long, at: Long? = null): Boolean = db.transactionWithResult {
        val state = q.oneOffStateById(id).executeAsOneOrNull() ?: return@transactionWithResult false
        if (state.deleted_at != null) return@transactionWithResult false
        val time = at ?: journal.now()
        q.deleteOneOff(at = time, hlc = record(this, id, mapOf("deleted_at" to v(time)), time), id = id)
        true
    }

    /** Removes tombstones older than [before], and their journal entries except the graves. Returns how many. */
    fun purgeTombstones(before: Long): Long = db.transactionWithResult {
        q.purgeOneOffTombstones(before).value.also { journal.purgeEntries(SyncTable.ONE_OFF_REMINDER) }
    }

    /** Gives reminders made before this device had an id, or by an older Pebble without uids, a uid and [deviceId]. */
    fun claim(deviceId: String) = db.transaction {
        q.fillOneOffUids()
        q.claimOneOffs(deviceId)
    }

    /** Live list of one-off reminders still to come, for the Reminders page. */
    fun pendingOneOffsFlow(context: CoroutineContext = Dispatchers.Default): Flow<List<OneOffReminder>> =
        q.pendingOneOffs().asFlow().mapToList(context)
            .map { rows -> rows.map(::toOneOff) }

    private fun toOneOff(r: One_off_reminder) =
        OneOffReminder(r.id, r.title, r.due_at, Strictness.parse(r.strictness), r.uid.takeIf { r.event_uid == null })

    /**
     * A reminder for one occurrence of a calendar event (WP E2), linked by [eventUid] and [occurrenceAt]. Returns false
     * when that occurrence already has a live reminder (pending, done or snoozed), so a second call adds nothing, and
     * when the event was deleted in the meantime (the agenda read it before the delete). With [eventHlc] (the HLC of the
     * event version that the agenda read), it also returns false when the event changed since (moved or edited).
     */
    fun addLinked(
        title: String,
        dueAt: Long,
        eventUid: String,
        occurrenceAt: Long,
        at: Long,
        eventHlc: String? = null,
    ): Boolean = db.transactionWithResult {
        if (q.linkedOneOffExists(eventUid, occurrenceAt).executeAsOne() > 0L) return@transactionWithResult false
        if (q.linkedEventDeleted(eventUid).executeAsOne() > 0L) return@transactionWithResult false
        if (eventHlc != null && q.linkedEventChanged(eventUid, eventHlc).executeAsOne() > 0L) return@transactionWithResult false
        q.insertLinkedOneOff(title, dueAt, Strictness.NORMAL.name, at, eventUid, occurrenceAt)
        true
    }

    /** The event [eventUid] changed or was deleted: its reminders that did not fire yet become tombstones. */
    fun deletePendingLinked(eventUid: String, at: Long) = q.deletePendingLinked(at, eventUid)

    /** Marks reminder [id] done. Returns false, and writes nothing, if it is gone, deleted or done already. */
    fun markOneOffDone(id: Long, at: Long): Boolean = db.transactionWithResult {
        val state = q.oneOffStateById(id).executeAsOneOrNull() ?: return@transactionWithResult false
        if (state.deleted_at != null || state.done_at != null) return@transactionWithResult false
        q.markOneOffDone(at = at, hlc = record(this, id, mapOf("done_at" to v(at)), at), id = id)
        true
    }

    /** Moves reminder [id] to [dueAt]. Returns false, and writes nothing, if it is gone or deleted, or is due then already. */
    fun rescheduleOneOff(id: Long, dueAt: Long, at: Long? = null): Boolean = db.transactionWithResult {
        val state = q.oneOffStateById(id).executeAsOneOrNull() ?: return@transactionWithResult false
        if (state.deleted_at != null || state.due_at == dueAt) return@transactionWithResult false
        val time = at ?: journal.now()
        q.rescheduleOneOff(dueAt = dueAt, at = time, hlc = record(this, id, mapOf("due_at" to v(dueAt)), time), id = id)
        true
    }

    /**
     * The HLC of the journal entries for [values] of reminder [id], or null if it is not synced: an event made it,
     * or it has no uid yet (reconcile records it).
     */
    private fun record(tx: TransactionCallbacks, id: Long, values: Map<String, JsonElement>, at: Long): String? {
        val info = q.oneOffSyncInfo(id).executeAsOneOrNull() ?: return null
        val uid = info.uid?.takeIf { info.event_uid == null } ?: return null
        return journal.record(tx, SyncTable.ONE_OFF_REMINDER, uid, values, at).toString()
    }
}
