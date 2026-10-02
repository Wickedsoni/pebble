package dev.pebble.core.reminders

import dev.pebble.db.PebbleDatabase

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

    /** Returns the new reminder's id. */
    fun addOneOff(title: String, dueAt: Long, strictness: Strictness = Strictness.NORMAL): Long = db.transactionWithResult {
        q.insertOneOff(title, dueAt, strictness.name)
        q.lastOneOffId().executeAsOne()
    }

    /** Removes a reminder outright (used to undo one Pebble created by mistake). */
    fun deleteOneOff(id: Long) = q.deleteOneOff(id)

    fun markOneOffDone(id: Long, at: Long) = q.markOneOffDone(at, id)

    fun rescheduleOneOff(id: Long, dueAt: Long) = q.rescheduleOneOff(dueAt, id)
}
