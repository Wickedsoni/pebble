package dev.pebble.core.sync

import dev.pebble.core.settings.DeviceIdentity
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * The tables and fields that sync copies (spec section 5): the only list. Other columns are local
 * (`id`, `hlc`, `updated_at`, `origin_device`, `event_uid`, `occurrence_at`). Reminders that an event made
 * (`event_uid` not null) are not synced: each device makes its own (spec D3).
 */
enum class SyncTable(val sqlName: String, val fields: List<String>) {
    NOTE("note", listOf("text", "created_at", "archived", "deleted_at")),
    ONE_OFF_REMINDER("one_off_reminder", listOf("title", "due_at", "strictness", "done_at", "deleted_at")),
    CALENDAR_EVENT(
        "calendar_event",
        listOf(
            "title", "notes", "start_at", "end_at", "all_day", "tz", "rrule", "exdates",
            "remind_minutes", "visibility", "owner_device", "deleted_at",
        ),
    ),
}

/** What [ChangeJournal.reconcile] did: rows that had no journal yet, changes made without it, and rows gone without a grave. */
data class Reconciled(val backfilled: Int, val repaired: Int, val graves: Int) {
    val total: Int get() = backfilled + repaired + graves
}

/**
 * The change journal (WP E3a, ADR 0018): the newest value and HLC of each synced field of each synced row.
 *
 * A repository writes a synced field in one transaction: first [record] (journal entries, one HLC), then the row
 * with `hlc` = that HLC. Guard triggers stop a write by new code that sets a new HLC without its entries. Writes
 * that keep the HLC (an older Pebble on the same file) are found and recorded by [reconcile] at the next start.
 * The journal keeps no state in memory, so any number of instances on one database agree.
 */
class ChangeJournal(private val db: PebbleDatabase) {
    private val q get() = db.journalQueries

    /** This device's id (made if there is none yet). */
    fun deviceId(): String = DeviceIdentity.ensure(SettingsRepository(db))

    /** SQLite's clock in milliseconds (whole seconds), for writes that got no time. */
    fun now(): Long = q.nowMillis().executeAsOne()

    /**
     * Writes one journal entry for each of [values] (field to JSON value), all with one new HLC at [wall], and
     * returns that HLC. Call it in the transaction that then writes the row with this HLC.
     */
    fun record(table: SyncTable, uid: String, values: Map<String, JsonElement>, wall: Long): Hlc = db.transactionWithResult {
        val unknown = values.keys - table.fields.toSet()
        require(unknown.isEmpty()) { "${table.sqlName}: not synced fields $unknown" }
        val last = q.maxHlc().executeAsOne().hlc?.let(Hlc::parse)
        val hlc = HlcClock.send(last, wall, deviceId())
        values.forEach { (field, value) -> q.recordEntry(table.sqlName, uid, field, value.toString(), hlc.toString()) }
        hlc
    }

    /** True if the journal keeps a delete for [uid]: a tombstone, or a grave of a purged row. */
    fun isDeleted(table: SyncTable, uid: String): Boolean = q.entriesOfRow(table.sqlName, uid).executeAsList()
        .any { it.field_ == "deleted_at" && parse(it.value_) != JsonNull }

    /** After a purge of tombstones: removes their entries, except the graves (spec 6.5). */
    fun purgeEntries(table: SyncTable): Long = when (table) {
        SyncTable.NOTE -> q.purgeNoteEntries().value
        SyncTable.ONE_OFF_REMINDER -> q.purgeReminderEntries().value
        SyncTable.CALENDAR_EVENT -> q.purgeEventEntries().value
    }

    /**
     * Makes the journal agree with the tables, one transaction per table. Run it at each start:
     * - a synced row with no entries (made before E3, or by an older Pebble) gets entries for all its fields;
     * - a field whose value differs from its entry (changed by an older Pebble) gets a new entry;
     * - a row that is gone with no grave (an older Pebble deleted it with DELETE) gets a grave at [wall].
     */
    fun reconcile(wall: Long): Reconciled {
        var backfilled = 0
        var repaired = 0
        var graves = 0
        for (table in SyncTable.entries) {
            db.transaction {
                val rows = rows(table)
                val entries = entries(table)
                for ((uid, row) in rows) {
                    val have = entries[uid]
                    val differ = table.fields.filter { have?.get(it) != row.values[it] }
                    if (differ.isEmpty()) continue
                    if (have == null) backfilled++ else repaired++
                    val hlc = record(table, uid, differ.associateWith { row.values.getValue(it) }, wall)
                    setHlc(table, uid, hlc)
                }
                for ((uid, have) in entries - rows.keys) {
                    if ((have["deleted_at"] ?: JsonNull) == JsonNull) {
                        record(table, uid, mapOf("deleted_at" to JsonPrimitive(wall)), wall)
                        graves++
                    }
                    q.dropAllButGrave(table.sqlName, uid)
                }
            }
        }
        return Reconciled(backfilled, repaired, graves)
    }

    /** Where the journal and the tables do not agree, one line for each field (empty = they agree). For tests. */
    fun verify(): List<String> = SyncTable.entries.flatMap { table ->
        val rows = rows(table)
        val entries = entries(table)
        rows.flatMap { (uid, row) ->
            table.fields.mapNotNull { f ->
                val have = entries[uid]?.get(f)
                if (have != row.values[f]) "${table.sqlName} $uid $f: row ${row.values[f]}, journal $have" else null
            } + listOfNotNull(if (row.hlc == null) "${table.sqlName} $uid: no hlc" else null)
        } + (entries.keys - rows.keys).mapNotNull { uid ->
            val extra = entries.getValue(uid).keys - "deleted_at"
            if (extra.isNotEmpty()) "${table.sqlName} $uid: row gone, entries $extra kept" else null
        }
    }

    private class Row(val values: Map<String, JsonElement>, val hlc: String?)

    private fun entries(table: SyncTable): Map<String, Map<String, JsonElement>> =
        q.entriesOfTable(table.sqlName).executeAsList().groupBy({
            it.uid
        }, { it.field_ to parse(it.value_) }).mapValues { it.value.toMap() }

    private fun rows(table: SyncTable): Map<String, Row> = when (table) {
        SyncTable.NOTE -> db.wellnessQueries.syncNotes().executeAsList().associate {
            it.uid to Row(
                mapOf(
                    "text" to v(it.text),
                    "created_at" to v(it.created_at),
                    "archived" to v(it.archived),
                    "deleted_at" to v(it.deleted_at),
                ),
                it.hlc?.takeIf(String::isNotEmpty),
            )
        }

        SyncTable.ONE_OFF_REMINDER -> db.remindersQueries.syncOneOffs().executeAsList().associate {
            it.uid to Row(
                mapOf(
                    "title" to v(it.title),
                    "due_at" to v(it.due_at),
                    "strictness" to v(it.strictness),
                    "done_at" to v(it.done_at),
                    "deleted_at" to v(it.deleted_at),
                ),
                it.hlc?.takeIf(String::isNotEmpty),
            )
        }

        SyncTable.CALENDAR_EVENT -> db.calendarQueries.syncEvents().executeAsList().associate {
            it.uid to Row(
                mapOf(
                    "title" to v(it.title), "notes" to v(it.notes), "start_at" to v(it.start_at), "end_at" to v(it.end_at),
                    "all_day" to v(it.all_day), "tz" to v(it.tz), "rrule" to v(it.rrule), "exdates" to v(it.exdates),
                    "remind_minutes" to v(it.remind_minutes), "visibility" to v(it.visibility),
                    "owner_device" to v(it.owner_device), "deleted_at" to v(it.deleted_at),
                ),
                it.hlc?.takeIf(String::isNotEmpty),
            )
        }
    }

    private fun setHlc(table: SyncTable, uid: String, hlc: Hlc) = when (table) {
        SyncTable.NOTE -> db.wellnessQueries.setNoteHlc(hlc.toString(), uid)
        SyncTable.ONE_OFF_REMINDER -> db.remindersQueries.setOneOffHlc(hlc.toString(), uid)
        SyncTable.CALENDAR_EVENT -> db.calendarQueries.setEventHlc(hlc.toString(), uid)
    }

    companion object {
        private val json = Json

        /** The JSON value of a column: a string, a whole number, or null. */
        fun v(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

        fun v(n: Long?): JsonElement = n?.let(::JsonPrimitive) ?: JsonNull

        private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

        /** A new uid for a synced row: 128 random bits as 32 lower-case hex digits (the form of the E1 uids). */
        fun newUid(random: Random = Random.Default): String = random.nextBytes(16).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
