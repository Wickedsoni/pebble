package dev.pebble.core.sync

import dev.pebble.core.settings.DeviceIdentity
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

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

    // ------------------------------------------------------------------ merge (WP E3b, spec section 7)

    /** The id of this copy of the journal (made if there is none yet). A restore makes a new one (spec 8). */
    fun epoch(): String {
        val settings = SettingsRepository(db)
        return settings.get(SettingsRepository.Keys.JOURNAL_EPOCH)
            ?: DeviceIdentity.newId().also { settings.set(SettingsRepository.Keys.JOURNAL_EPOCH, it) }
    }

    /**
     * The changes after [cursor], as whole rows (spec 7.1): the entries with a larger `seq`, up to [limit] entries, and for
     * each of their rows all its entries, so that a peer can insert a row it does not have. A null cursor, or one of
     * another epoch (a restore), reads from the start.
     */
    fun changesSince(cursor: Cursor?, limit: Int = 500): ChangeBatch = db.transactionWithResult {
        val epoch = epoch()
        val from = if (cursor?.epoch == epoch) cursor.seq else 0L
        val picked = q.entriesAfter(from, limit.toLong()).executeAsList()
        val rows = picked.map { it.tbl to it.uid }.distinct().map { (tbl, uid) ->
            RowChange(tbl, uid, q.rowEntries(tbl, uid).executeAsList().associate { it.field_ to Stamped(parse(it.value_), it.hlc) })
        }
        ChangeBatch(epoch, rows, Cursor(epoch, picked.lastOrNull()?.seq ?: from))
    }

    /**
     * Merges a peer's [batch] at this device's time [wall] (spec 7.2, 7.3): field by field, the larger HLC wins, and a
     * delete always wins (D5). All or nothing: a batch with a bad name, HLC or value, a clock too far ahead, or a new row
     * without all its fields changes nothing. The same batch again changes nothing.
     */
    fun apply(batch: ChangeBatch, wall: Long): ApplyResult {
        val checked = try {
            check(batch, wall)
        } catch (e: Refusal) {
            return ApplyResult.Refused(e.reason)
        }
        return try {
            db.transactionWithResult { merge(checked, wall) }
        } catch (e: Refusal) {
            ApplyResult.Refused(e.reason)
        } catch (e: Exception) {
            ApplyResult.Refused("the database refused the batch: ${e.message}")
        }
    }

    private class Refusal(val reason: String) : Exception(reason)

    private class Change(val value: JsonElement, val hlc: Hlc)

    private class CheckedRow(val table: SyncTable, val uid: String, val fields: Map<String, Change>)

    private fun check(batch: ChangeBatch, wall: Long): List<CheckedRow> {
        val rows = batch.rows.map { rc ->
            val table = SyncTable.of(rc.table) ?: throw Refusal("unknown table ${rc.table}")
            if (rc.uid.isEmpty() || rc.uid.length > MAX_UID || rc.uid.any { it.isISOControl() }) throw Refusal("bad uid in ${rc.table}")
            CheckedRow(
                table,
                rc.uid,
                rc.fields.mapValues { (name, s) ->
                    val field = table.column(name) ?: throw Refusal("${table.sqlName}.$name is not synced")
                    val hlc = Hlc.parse(s.hlc) ?: throw Refusal("bad HLC for ${table.sqlName}.$name")
                    if (!field.accepts(s.value)) throw Refusal("bad value for ${table.sqlName}.$name")
                    Change(s.value, hlc)
                },
            )
        }
        rows.flatMap { it.fields.values }.maxOfOrNull { it.hlc }?.let { newest ->
            try {
                HlcClock.receive(null, newest, wall)
            } catch (e: ClockAheadException) {
                throw Refusal("the peer's clock is ahead: ${e.message}")
            }
        }
        return rows
    }

    private fun merge(rows: List<CheckedRow>, wall: Long): ApplyResult.Applied {
        var rowsChanged = 0
        var fieldsChanged = 0
        var dropped = 0
        val events = linkedSetOf<String>()
        for (row in rows) {
            val table = row.table
            val uid = row.uid
            val incoming = row.fields
            if (incoming.isEmpty()) continue
            val local = q.rowEntries(table.sqlName, uid).executeAsList()
                .associate { it.field_ to Change(parse(it.value_), Hlc.parse(it.hlc) ?: throw Refusal("bad local HLC ${it.hlc}")) }
            val current = currentRow(table, uid)
            val changed: Map<String, Change>
            if (current == null) {
                // Not here. If the journal knows the row, this device removed it (a purged tombstone): drop the change.
                if (local.isNotEmpty() || isLocalOnly(table, uid)) {
                    dropped++
                    continue
                }
                if (incoming.keys != table.fields.toSet()) {
                    throw Refusal("new ${table.sqlName} row $uid lacks ${table.fields - incoming.keys}")
                }
                val newest = incoming.values.maxOf { it.hlc }
                insert(table, uid, incoming.mapValues { it.value.value }, newest.wallMillis, incoming.values.minOf { it.hlc }.device)
                incoming.forEach { (f, c) -> q.recordEntry(table.sqlName, uid, f, c.value.toString(), c.hlc.toString()) }
                setHlc(table, uid, newest)
                changed = incoming
            } else {
                changed = incoming.filter { (f, c) -> wins(f, c, local[f]) }
                keepLost(table, uid, incoming - changed.keys, current, wall)
                if (changed.isEmpty()) continue
                changed.forEach { (f, c) -> q.recordEntry(table.sqlName, uid, f, c.value.toString(), c.hlc.toString()) }
                // One update for each HLC, oldest first: each one changes only fields journaled with its HLC (guard triggers).
                val values = current.toMutableMap()
                changed.entries.groupBy { it.value.hlc }.toSortedMap().forEach { (hlc, group) ->
                    group.forEach { values[it.key] = it.value.value }
                    update(table, uid, values, hlc)
                }
            }
            rowsChanged++
            fieldsChanged += changed.size
            if (table == SyncTable.CALENDAR_EVENT) events += uid
        }
        return ApplyResult.Applied(rowsChanged, fieldsChanged, events.toList(), dropped)
    }

    /**
     * Keeps the peer's [losers] in `change_history` as lost edits (WP E3c, spec 4.2): not `deleted_at`, not a value
     * equal to this row's value, and not an edit of this device (a peer that sends back an old value of ours).
     * An old value that the history keeps already as replaced stays one row (the unique key).
     */
    private fun keepLost(table: SyncTable, uid: String, losers: Map<String, Change>, current: Map<String, JsonElement>, wall: Long) {
        val me = deviceId()
        losers.forEach { (f, c) ->
            if (f == "deleted_at" || c.value == current[f] || c.hlc.device == me) return@forEach
            db.changeHistoryQueries.keepLost(table.sqlName, uid, f, c.value.toString(), c.hlc.toString(), wall)
        }
    }

    /**
     * The synced fields of row [uid] now, also a tombstone; null if this device has no such synced row (WP E3c).
     */
    fun fields(table: SyncTable, uid: String): Map<String, JsonElement>? = currentRow(table, uid)

    /**
     * A local edit of [values] of the live row [uid] at [wall], through the merge's update path (WP E3c, a restore):
     * one new HLC for the journal entries, then the row. Only values that differ are written. Returns false, and
     * changes nothing, if the row is gone or deleted. `deleted_at` cannot be written here.
     */
    fun edit(table: SyncTable, uid: String, values: Map<String, JsonElement>, wall: Long): Boolean = db.transactionWithResult {
        require("deleted_at" !in values) { "a delete is not an edit" }
        val current = currentRow(table, uid)?.takeIf { it["deleted_at"] == JsonNull } ?: return@transactionWithResult false
        val changed = values.filter { (f, value) -> current[f] != value }
        if (changed.isEmpty()) return@transactionWithResult true
        changed.forEach { (f, value) -> require(table.column(f)?.accepts(value) == true) { "${table.sqlName}.$f: bad value $value" } }
        val hlc = record(table, uid, changed, wall)
        update(table, uid, current + changed, hlc)
        true
    }

    /** Spec 7.2: the larger HLC wins; for `deleted_at`, order by (deleted, HLC), so a delete always wins (D5). */
    private fun wins(field: String, remote: Change, local: Change?): Boolean {
        if (local == null) return true
        if (field == "deleted_at") {
            val remoteDeleted = remote.value != JsonNull
            val localDeleted = local.value != JsonNull
            if (remoteDeleted != localDeleted) return remoteDeleted
        }
        return remote.hlc > local.hlc
    }

    /** A reminder that an event made here has this uid: never merged (spec D3). */
    private fun isLocalOnly(table: SyncTable, uid: String): Boolean =
        table == SyncTable.ONE_OFF_REMINDER && db.remindersQueries.syncOneOffByUid(uid).executeAsOneOrNull() != null

    private fun currentRow(table: SyncTable, uid: String): Map<String, JsonElement>? = when (table) {
        SyncTable.NOTE -> db.wellnessQueries.syncNoteByUid(uid).executeAsOneOrNull()?.let {
            mapOf("text" to v(it.text), "created_at" to v(it.created_at), "archived" to v(it.archived), "deleted_at" to v(it.deleted_at))
        }

        SyncTable.ONE_OFF_REMINDER -> db.remindersQueries.syncOneOffByUid(uid).executeAsOneOrNull()?.takeIf { it.event_uid == null }?.let {
            mapOf(
                "title" to v(it.title),
                "due_at" to v(it.due_at),
                "strictness" to v(it.strictness),
                "done_at" to v(it.done_at),
                "deleted_at" to v(it.deleted_at),
            )
        }

        SyncTable.CALENDAR_EVENT -> db.calendarQueries.eventRowByUid(uid).executeAsOneOrNull()?.let {
            mapOf(
                "title" to v(it.title), "notes" to v(it.notes), "start_at" to v(it.start_at), "end_at" to v(it.end_at),
                "all_day" to v(it.all_day), "tz" to v(it.tz), "rrule" to v(it.rrule), "exdates" to v(it.exdates),
                "remind_minutes" to v(it.remind_minutes), "visibility" to v(it.visibility),
                "owner_device" to v(it.owner_device), "deleted_at" to v(it.deleted_at),
            )
        }
    }

    private fun insert(table: SyncTable, uid: String, f: Map<String, JsonElement>, updatedAt: Long, origin: String) {
        when (table) {
            SyncTable.NOTE -> db.wellnessQueries.mergeInsertNote(
                uid = uid,
                text = f.str("text")!!,
                createdAt = f.long("created_at")!!,
                archived = f.long("archived")!!,
                deletedAt = f.long("deleted_at"),
                updatedAt = updatedAt,
                originDevice = origin,
            )

            SyncTable.ONE_OFF_REMINDER -> db.remindersQueries.mergeInsertOneOff(
                uid = uid,
                title = f.str("title")!!,
                dueAt = f.long("due_at")!!,
                strictness = f.str("strictness")!!,
                doneAt = f.long("done_at"),
                deletedAt = f.long("deleted_at"),
                updatedAt = updatedAt,
                originDevice = origin,
            )

            SyncTable.CALENDAR_EVENT -> db.calendarQueries.mergeInsertEvent(
                uid = uid, title = f.str("title")!!, notes = f.str("notes"), startAt = f.long("start_at")!!, endAt = f.long("end_at")!!,
                allDay = f.long("all_day")!!, tz = f.str("tz")!!, rrule = f.str("rrule"), exdates = f.str("exdates"),
                remindMinutes = f.long("remind_minutes"), visibility = f.str("visibility")!!, ownerDevice = f.str("owner_device"),
                deletedAt = f.long("deleted_at"), updatedAt = updatedAt, originDevice = origin,
            )
        }
    }

    private fun update(table: SyncTable, uid: String, f: Map<String, JsonElement>, hlc: Hlc) {
        val h = hlc.toString()
        when (table) {
            SyncTable.NOTE -> db.wellnessQueries.mergeUpdateNote(
                text = f.str("text")!!,
                createdAt = f.long("created_at")!!,
                archived = f.long("archived")!!,
                deletedAt = f.long("deleted_at"),
                updatedAt = hlc.wallMillis,
                hlc = h,
                uid = uid,
            )

            SyncTable.ONE_OFF_REMINDER -> db.remindersQueries.mergeUpdateOneOff(
                title = f.str("title")!!,
                dueAt = f.long("due_at")!!,
                strictness = f.str("strictness")!!,
                doneAt = f.long("done_at"),
                deletedAt = f.long("deleted_at"),
                updatedAt = hlc.wallMillis,
                hlc = h,
                uid = uid,
            )

            SyncTable.CALENDAR_EVENT -> db.calendarQueries.mergeUpdateEvent(
                title = f.str("title")!!, notes = f.str("notes"), startAt = f.long("start_at")!!, endAt = f.long("end_at")!!,
                allDay = f.long("all_day")!!, tz = f.str("tz")!!, rrule = f.str("rrule"), exdates = f.str("exdates"),
                remindMinutes = f.long("remind_minutes"), visibility = f.str("visibility")!!, ownerDevice = f.str("owner_device"),
                deletedAt = f.long("deleted_at"), updatedAt = hlc.wallMillis, hlc = h, uid = uid,
            )
        }
    }

    private fun Map<String, JsonElement>.str(field: String): String? = (getValue(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun Map<String, JsonElement>.long(field: String): Long? = (getValue(field) as? JsonPrimitive)?.takeIf {
        !it.isString
    }?.longOrNull

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

        /** Calendar uids come from ICS files too, so they are any short text without control characters. */
        private const val MAX_UID = 255

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
