package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.sync.ChangeHistory
import dev.pebble.core.sync.DeletedItem
import dev.pebble.core.sync.SyncTable
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The "Recently deleted" card of the Memory page (WP E3c-2), ready to draw. */
@Immutable
data class RecentlyDeletedUiState(
    val items: List<DeletedRow> = emptyList(),
    /** True after "Clear history": the card asks before it deletes. */
    val confirmClear: Boolean = false,
    /** The result of the last action; null: the hint. */
    val message: String? = null,
) {
    @Immutable
    data class DeletedRow(val table: SyncTable, val uid: String, val kindLabel: String, val title: String, val deletedLabel: String)
}

/** What you can do on the "Recently deleted" card. */
sealed interface RecentlyDeletedEvent {
    data class RestoreCopy(val table: SyncTable, val uid: String) : RecentlyDeletedEvent

    data object AskClear : RecentlyDeletedEvent

    data object CancelClear : RecentlyDeletedEvent

    data object ConfirmClear : RecentlyDeletedEvent
}

/**
 * State holder for the "Recently deleted" card (docs/UI-PATTERN.md, spec E3C-HISTORY section 6). It lists deleted
 * events, notes and reminders of the last 90 days. "Restore as a copy" makes a new item; the deleted one stays deleted.
 * "Clear history" deletes all old versions on this device. [state] updates when an item is deleted elsewhere.
 *
 * Threads: runs on [scope]'s dispatcher (the UI thread in the app). Database work goes to `env.dispatchers.io`;
 * [ReminderEngine.tick] stays on [scope]'s thread.
 */
class RecentlyDeletedStateHolder(
    private val history: ChangeHistory,
    notes: NoteRepository,
    reminders: ReminderRepository,
    calendar: CalendarRepository,
    private val agenda: CalendarAgenda,
    private val engine: ReminderEngine,
    private val env: AppEnv,
    private val scope: CoroutineScope,
) {
    private data class View(val confirmClear: Boolean = false, val message: String? = null)

    private val view = MutableStateFlow(View())

    /** Bumped after a change that the flows below do not report (a copy of a done reminder, a clear). */
    private val refresh = MutableStateFlow(0)

    val state: StateFlow<RecentlyDeletedUiState> =
        combine(
            view,
            refresh,
            notes.activeFlow(context = env.dispatchers.io),
            reminders.pendingOneOffsFlow(env.dispatchers.io),
            calendar.liveFlow(env.dispatchers.io),
        ) { v, _, _, _, _ -> build(v) }
            .stateIn(scope, SharingStarted.Eagerly, build(view.value))

    fun onEvent(e: RecentlyDeletedEvent) {
        when (e) {
            is RecentlyDeletedEvent.RestoreCopy -> write {
                val item = history.recentlyDeleted(env.millis()).firstOrNull { it.table == e.table && it.uid == e.uid }
                val copy = item?.let { history.restoreCopy(it, env.millis()) }
                if (copy != null && e.table == SyncTable.CALENDAR_EVENT) agenda.eventChanged(copy, env.millis())
                if (copy != null) "Restored “${item.title.short()}” as a copy." else "That item is no longer here."
            }

            RecentlyDeletedEvent.AskClear -> view.update { it.copy(confirmClear = true, message = null) }

            RecentlyDeletedEvent.CancelClear -> view.update { it.copy(confirmClear = false) }

            RecentlyDeletedEvent.ConfirmClear -> write {
                val n = history.clear()
                "Deleted $n history ${if (n == 1L) "entry" else "entries"}. Deleted items stay here until 90 days pass."
            }
        }
    }

    private fun write(work: () -> String) {
        scope.launch {
            val message = withContext(env.dispatchers.io) {
                runCatching(work).getOrElse { "That did not work: ${it.message ?: it::class.simpleName}" }
            }
            engine.tick()
            view.update { it.copy(confirmClear = false, message = message) }
            refresh.value++
        }
    }

    private fun build(v: View) = RecentlyDeletedUiState(
        items = history.recentlyDeleted(env.millis()).map(::row),
        confirmClear = v.confirmClear,
        message = v.message,
    )

    private fun row(d: DeletedItem) = RecentlyDeletedUiState.DeletedRow(
        table = d.table,
        uid = d.uid,
        kindLabel = when (d.table) {
            SyncTable.NOTE -> "Note"
            SyncTable.ONE_OFF_REMINDER -> "Reminder"
            SyncTable.CALENDAR_EVENT -> "Event"
        },
        title = d.title,
        deletedLabel = "deleted " + Instant.ofEpochMilli(d.deletedAt).atZone(env.zone()).format(WHEN),
    )

    private fun String.short() = if (length > 40) take(40) + "…" else this

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    }
}
