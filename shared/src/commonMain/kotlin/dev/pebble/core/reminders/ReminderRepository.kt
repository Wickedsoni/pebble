package dev.pebble.core.reminders

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.core.db.writeTransaction
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

data class OneOffReminder(val id: Long, val title: String, val dueAt: Long, val strictness: Strictness)

class ReminderRepository(private val db: PebbleDatabase) {
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

    fun pendingOneOffs(): List<OneOffReminder> = q.pendingOneOffs().executeAsList().map {
        OneOffReminder(it.id, it.title, it.due_at, Strictness.parse(it.strictness))
    }

    /** Returns the new reminder's id. [at]: when it was made (null: now). */
    fun addOneOff(
        title: String,
        dueAt: Long,
        strictness: Strictness = Strictness.NORMAL,
        at: Long? = null,
    ): Long = db.transactionWithResult {
        q.insertOneOff(title, dueAt, strictness.name, at)
        q.lastOneOffId().executeAsOne()
    }

    /**
     * Removes a reminder from view (used to undo one Pebble created by mistake). The row stays as a tombstone
     * (`deleted_at`) so that sync can tell other devices (WP E1); [purgeTombstones] removes it later.
     */
    fun deleteOneOff(id: Long, at: Long? = null) = q.deleteOneOff(at, id)

    /** Removes tombstones older than [before]. Returns how many. */
    fun purgeTombstones(before: Long): Long = q.purgeOneOffTombstones(before).value

    /** Gives reminders made before this device had an id, or by an older Pebble without uids, a uid and [deviceId]. */
    fun claim(deviceId: String) = db.transaction {
        q.fillOneOffUids()
        q.claimOneOffs(deviceId)
    }

    /** Live list of one-off reminders still to come, for the Reminders page. */
    fun pendingOneOffsFlow(context: CoroutineContext = Dispatchers.Default): Flow<List<OneOffReminder>> =
        q.pendingOneOffs().asFlow().mapToList(context)
            .map { rows -> rows.map { OneOffReminder(it.id, it.title, it.due_at, Strictness.parse(it.strictness)) } }

    /**
     * A reminder for one occurrence of a calendar event (WP E2), linked by [eventUid] and [occurrenceAt]. Returns false
     * when that occurrence already has a live reminder (pending, done or snoozed), so a second call adds nothing.
     */
    fun addLinked(title: String, dueAt: Long, eventUid: String, occurrenceAt: Long, at: Long): Boolean = db.writeTransaction {
        if (q.linkedOneOffExists(eventUid, occurrenceAt).executeAsOne() > 0L) return@writeTransaction false
        q.insertLinkedOneOff(title, dueAt, Strictness.NORMAL.name, at, eventUid, occurrenceAt)
        true
    }

    /** The event [eventUid] changed or was deleted: its reminders that did not fire yet become tombstones. */
    fun deletePendingLinked(eventUid: String, at: Long) = q.deletePendingLinked(at, eventUid)

    fun markOneOffDone(id: Long, at: Long) = q.markOneOffDone(at, id)

    fun rescheduleOneOff(id: Long, dueAt: Long, at: Long? = null) = q.rescheduleOneOff(dueAt, at, id)
}
