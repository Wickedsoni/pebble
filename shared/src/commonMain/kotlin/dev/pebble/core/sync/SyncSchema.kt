package dev.pebble.core.sync

import dev.pebble.core.reminders.Strictness
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The SQLite type of a synced field. */
enum class FieldType { TEXT, INT }

/**
 * One synced field and the values it accepts (spec 7.3): its [type], null or not, and for some fields a fixed set
 * ([allowed]) or 0/1 ([flag]). The merge refuses a batch with any other value.
 */
data class SyncField(
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val allowed: Set<String>? = null,
    val flag: Boolean = false,
) {
    fun accepts(value: JsonElement): Boolean {
        if (value == JsonNull) return nullable
        val p = value as? JsonPrimitive ?: return false
        return when (type) {
            FieldType.TEXT -> p.isString && p.content.length <= MAX_TEXT && (allowed == null || p.content in allowed)
            FieldType.INT -> !p.isString && p.longOrNull?.let { !flag || it == 0L || it == 1L } == true
        }
    }

    companion object {
        /** Longer than any note or title that Pebble writes (a note is a few lines). */
        const val MAX_TEXT = 100_000
    }
}

private fun text(name: String, allowed: Set<String>? = null) = SyncField(name, FieldType.TEXT, nullable = false, allowed = allowed)

private fun textOrNull(name: String) = SyncField(name, FieldType.TEXT, nullable = true)

private fun int(name: String) = SyncField(name, FieldType.INT, nullable = false)

private fun intOrNull(name: String) = SyncField(name, FieldType.INT, nullable = true)

private fun flag(name: String) = SyncField(name, FieldType.INT, nullable = false, flag = true)

/**
 * The tables and fields that sync copies (spec section 5): the only list. Other columns are local
 * (`id`, `hlc`, `updated_at`, `origin_device`, `event_uid`, `occurrence_at`). Reminders that an event made
 * (`event_uid` not null) are not synced: each device makes its own (spec D3).
 */
enum class SyncTable(val sqlName: String, val columns: List<SyncField>) {
    NOTE("note", listOf(text("text"), int("created_at"), flag("archived"), intOrNull("deleted_at"))),
    ONE_OFF_REMINDER(
        "one_off_reminder",
        listOf(
            text("title"),
            int("due_at"),
            text("strictness", Strictness.entries.map { it.name }.toSet()),
            intOrNull("done_at"),
            intOrNull("deleted_at"),
        ),
    ),
    CALENDAR_EVENT(
        "calendar_event",
        listOf(
            text("title"), textOrNull("notes"), int("start_at"), int("end_at"), flag("all_day"), text("tz"),
            textOrNull("rrule"), textOrNull("exdates"), intOrNull("remind_minutes"),
            text("visibility", setOf("private", "busy", "full")), textOrNull("owner_device"), intOrNull("deleted_at"),
        ),
    ),
    ;

    val fields: List<String> = columns.map { it.name }

    fun column(name: String): SyncField? = columns.firstOrNull { it.name == name }

    companion object {
        fun of(sqlName: String): SyncTable? = entries.firstOrNull { it.sqlName == sqlName }
    }
}

/** How far a peer read this device's journal (spec 8): the journal epoch and the last `seq` it got. */
data class Cursor(val epoch: String, val seq: Long)

/** A field value with the HLC (text form) of its change, as a peer sends it. */
data class Stamped(val value: JsonElement, val hlc: String)

/** All journal entries of one row. Names are plain text: the receiver checks them against [SyncTable]. */
data class RowChange(val table: String, val uid: String, val fields: Map<String, Stamped>)

/** A batch of whole rows from [ChangeJournal.changesSince], and the cursor to ask from next. */
data class ChangeBatch(val epoch: String, val rows: List<RowChange>, val next: Cursor)

/** What [ChangeJournal.apply] did with a batch. */
sealed interface ApplyResult {
    /** [changedEvents]: calendar event uids that changed; the caller runs the agenda for them (spec 7.4). */
    data class Applied(val rowsChanged: Int, val fieldsChanged: Int, val changedEvents: List<String>, val dropped: Int = 0) : ApplyResult

    /** Nothing changed: the whole batch was refused, for [reason]. */
    data class Refused(val reason: String) : ApplyResult
}
