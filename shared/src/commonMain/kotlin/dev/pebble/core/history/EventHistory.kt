package dev.pebble.core.history

import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json

/**
 * Counts over the whole history of `event_log`, fast at any size: whole days before the roll-up
 * watermark come from `daily_stat` (see [HistoryCompactor]), later entries from the raw rows.
 * Before the first roll-up the watermark is 0, so everything is read raw, as before.
 */
class EventHistory(private val db: PebbleDatabase, private val dayOf: (Long) -> Long) {
    /** Entries before this time are counted in `daily_stat`. */
    fun watermark(): Long = db.pebbleQueries.selectSetting(Keys.HISTORY_ROLLED_UP_UNTIL).executeAsOneOrNull()?.toLongOrNull() ?: 0L

    /** Local days with at least one entry of [type]. */
    fun days(type: String): Set<Long> {
        val since = watermark()
        return db.historyQueries.statDays(type).executeAsList().toSet() +
            db.pebbleQueries.eventsOfTypeSince(type, since).executeAsList().map { dayOf(it.at_millis) }
    }

    /** How many entries of [type] there are. */
    fun count(type: String): Long {
        val since = watermark()
        return db.historyQueries.statSum(type).executeAsOne() + db.pebbleQueries.eventsOfTypeSince(type, since).executeAsList().size
    }

    /** How many entries of [type] have the detail [key] (see [keyOf]), e.g. reminder_acted with DONE. */
    fun count(type: String, key: String): Long {
        val since = watermark()
        return db.historyQueries.statSumForKey(type, key).executeAsOne() +
            db.pebbleQueries.eventsOfTypeSince(type, since).executeAsList().count { keyOf(it.payload) == key }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The detail of an entry that counts are kept for: a reminder's action ("DONE", "SNOOZED", "DISMISSED"),
         * otherwise "". Decoded with the event's own serializer, never by matching text in the JSON.
         */
        fun keyOf(
            payload: String,
        ): String = when (val e = runCatching { json.decodeFromString(PebbleEvent.serializer(), payload) }.getOrNull()) {
            is PebbleEvent.ReminderActed -> e.action.name
            else -> ""
        }
    }
}
