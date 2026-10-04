package dev.pebble.core.history

import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.db.PebbleDatabase

/**
 * Rolls whole old days of `event_log` into `daily_stat` (WP B6, ADR 0004), so [EventHistory] reads a few
 * rows per day instead of every entry. Nothing is deleted: the raw rows stay for learning and for the
 * offline nudge evaluation. Safe to call often; it only works when a new day is old enough.
 */
class HistoryCompactor(private val db: PebbleDatabase, private val dayOf: (Long) -> Long) {
    /**
     * Counts every entry before [until] (a local midnight) that isn't counted yet, and moves the watermark
     * there — one transaction, so a crash never counts a day twice. Returns how many entries it rolled up.
     */
    fun rollUpBefore(until: Long): Int {
        val history = EventHistory(db, dayOf)
        return db.transactionWithResult {
            val from = history.watermark()
            if (until <= from) return@transactionWithResult 0
            val rows = db.historyQueries.eventsBetween(from, until).executeAsList()
            rows.groupingBy { Triple(dayOf(it.at_millis), it.type, EventHistory.keyOf(it.payload)) }.eachCount()
                .forEach { (k, n) -> db.historyQueries.addDailyStat(k.first, k.second, k.third, n.toLong()) }
            db.pebbleQueries.upsertSetting(Keys.HISTORY_ROLLED_UP_UNTIL, until.toString())
            rows.size
        }
    }

    companion object {
        /** Days kept raw-only before they are counted; a little slack for late writes and clock changes. */
        const val KEEP_RAW_DAYS = 7
    }
}
