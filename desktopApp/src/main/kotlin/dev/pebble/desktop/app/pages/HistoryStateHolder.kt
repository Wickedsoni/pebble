package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.sync.ChangeHistory
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.SyncTable
import dev.pebble.core.sync.Version
import dev.pebble.desktop.command.formatMinutes
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The History dialog of an event or a reminder (WP E3c-2), ready to draw. [open] false: no dialog. */
@Immutable
data class HistoryUiState(
    val open: Boolean = false,
    val title: String = "",
    val versions: List<VersionRow> = emptyList(),
    /** The result of the last restore or delete; null: the hint. */
    val message: String? = null,
    /** True after "Delete old versions": the dialog asks before it deletes. */
    val confirmDelete: Boolean = false,
) {
    /**
     * One version, newest first. [changes]: "Title: old → now", one for each field that differs from now. [canRestore]:
     * false when the version is the same as now. [lostLabel]: "Lost in a sync conflict", or null.
     */
    @Immutable
    data class VersionRow(
        val index: Int,
        val whenLabel: String,
        val deviceLabel: String,
        val lostLabel: String?,
        val changes: List<String>,
        val canRestore: Boolean,
    )
}

/** What you can do in the History dialog. */
sealed interface HistoryEvent {
    data class Open(val table: SyncTable, val uid: String, val title: String) : HistoryEvent

    data object Close : HistoryEvent

    data class Restore(val index: Int) : HistoryEvent

    /** "Delete old versions" of this item: asks first. */
    data object AskDelete : HistoryEvent

    data object CancelDelete : HistoryEvent

    /** Deletes all old versions of this item (for example, private text that you edited out). */
    data object ConfirmDelete : HistoryEvent
}

/**
 * State holder for the History dialog (docs/UI-PATTERN.md, spec E3C-HISTORY section 6), shared by the Calendar and
 * Reminders pages. A restore is a new edit (it syncs). After a restore of an event, the agenda remakes its reminders.
 *
 * Threads: runs on [scope]'s dispatcher (the UI thread in the app). Database work goes to `env.dispatchers.io`;
 * [ReminderEngine.tick] stays on [scope]'s thread.
 */
class HistoryStateHolder(
    private val history: ChangeHistory,
    private val journal: ChangeJournal,
    private val agenda: CalendarAgenda,
    private val engine: ReminderEngine,
    private val env: AppEnv,
    private val scope: CoroutineScope,
) {
    private data class Item(val table: SyncTable, val uid: String, val title: String)

    private var item: Item? = null
    private var versions: List<Version> = emptyList()

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun onEvent(e: HistoryEvent) {
        when (e) {
            is HistoryEvent.Open -> {
                item = Item(e.table, e.uid, e.title)
                load(message = null)
            }

            HistoryEvent.Close -> {
                item = null
                versions = emptyList()
                _state.value = HistoryUiState()
            }

            is HistoryEvent.Restore -> restore(e.index)

            HistoryEvent.AskDelete -> _state.value = _state.value.copy(confirmDelete = true, message = null)

            HistoryEvent.CancelDelete -> _state.value = _state.value.copy(confirmDelete = false)

            HistoryEvent.ConfirmDelete -> deleteAll()
        }
    }

    private fun load(message: String?) {
        val it = item ?: return
        scope.launch {
            val (list, rows) = withContext(env.dispatchers.io) {
                val list = history.versions(it.table, it.uid)
                val now = journal.fields(it.table, it.uid).orEmpty()
                val me = journal.deviceId()
                list to list.mapIndexed { i, v -> row(i, v, now, me) }
            }
            if (item != it) return@launch
            versions = list
            _state.value = HistoryUiState(open = true, title = it.title, versions = rows, message = message)
        }
    }

    private fun deleteAll() {
        val it = item ?: return
        scope.launch {
            val n = withContext(env.dispatchers.io) { history.clear(it.table, it.uid) }
            load(
                "Deleted ${versions.size} old ${if (versions.size == 1) "version" else "versions"} ($n history ${if (n == 1L) "entry" else "entries"}).",
            )
        }
    }

    private fun restore(index: Int) {
        val v = versions.getOrNull(index) ?: return
        scope.launch {
            val done = withContext(env.dispatchers.io) {
                history.restore(v, env.millis()).also { ok ->
                    if (ok && v.table == SyncTable.CALENDAR_EVENT) agenda.eventChanged(v.uid, env.millis())
                }
            }
            engine.tick()
            load(
                if (done) {
                    "Restored the version of ${time(v.at)}."
                } else {
                    "This item was deleted. Use “Restore as a copy” on the Memory page."
                },
            )
        }
    }

    private fun row(i: Int, v: Version, now: Map<String, JsonElement>, me: String): HistoryUiState.VersionRow {
        val changes = v.fields.filter { (f, old) -> now[f] != old }.map { (f, old) ->
            "${label(f)}: ${show(f, old)} → ${now[f]?.let { show(f, it) } ?: "—"}"
        }
        return HistoryUiState.VersionRow(
            index = i,
            whenLabel = time(v.at),
            deviceLabel = if (v.device == me) "this device" else "another device",
            lostLabel = if (v.lost) "Lost in a sync conflict" else null,
            changes = changes.ifEmpty { listOf("The same as now.") },
            canRestore = changes.isNotEmpty(),
        )
    }

    private fun time(at: Long): String = Instant.ofEpochMilli(at).atZone(env.zone()).format(WHEN)

    private fun label(field: String): String = LABELS[field] ?: field

    /** A value as you read it: text in quotes, times in your zone, flags as words. */
    private fun show(field: String, value: JsonElement): String {
        val p = value as? JsonPrimitive
        val n = p?.takeIf { !it.isString }?.longOrNull
        return when {
            value == JsonNull -> when (field) {
                "done_at" -> "not done"
                "remind_minutes" -> "no reminder"
                "rrule" -> "once"
                "exdates" -> "no skipped days"
                else -> "empty"
            }

            field in TIMES && n != null -> time(n)

            field == "done_at" && n != null -> "done ${time(n)}"

            field == "archived" -> if (n == 1L) "archived" else "not archived"

            field == "all_day" -> if (n == 1L) "all day" else "timed"

            field == "remind_minutes" && n != null -> "${formatMinutes(n.toInt())} before"

            field == "exdates" -> p?.content.orEmpty().split(',').count {
                it.isNotBlank()
            }.let { "$it skipped ${if (it == 1) "day" else "days"}" }

            p != null && p.isString -> "“${p.content.take(MAX_SHOWN)}${if (p.content.length > MAX_SHOWN) "…" else ""}”"

            else -> value.toString()
        }
    }

    private companion object {
        const val MAX_SHOWN = 60
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
        val TIMES = setOf("due_at", "start_at", "end_at", "created_at")
        val LABELS = mapOf(
            "title" to "Title", "text" to "Text", "notes" to "Notes", "due_at" to "Time", "start_at" to "Start", "end_at" to "End",
            "created_at" to "Made", "done_at" to "Done", "archived" to "Archived", "strictness" to "Strictness",
            "all_day" to "All day", "tz" to "Time zone", "rrule" to "Repeats", "exdates" to "Skipped days",
            "remind_minutes" to "Reminder", "visibility" to "Visibility", "owner_device" to "Owner",
        )
    }
}
