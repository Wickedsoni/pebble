package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import dev.pebble.desktop.command.formatMinutes
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Everything the Reminders page shows, ready to draw: no repository, engine or clock is needed to render it. */
@Immutable
data class RemindersUiState(
    val rules: List<RuleRow> = emptyList(),
    val oneOffs: List<OneOffRow> = emptyList(),
    val upcoming: List<UpcomingRow> = emptyList(),
    /** What Pebble learned about your quiet hours; null until it learned something. */
    val learnedQuiet: String? = null,
) {
    @Immutable
    data class RuleRow(
        val id: String,
        val title: String,
        val intervalLabel: String,
        val strictness: Strictness,
        val enabled: Boolean,
    )

    /** [uid]: for its History dialog; null for a reminder that an event made (it has no history). */
    @Immutable
    data class OneOffRow(val id: Long, val title: String, val dueLabel: String, val uid: String? = null)

    @Immutable
    data class UpcomingRow(val key: String, val title: String, val dueLabel: String, val overdue: Boolean)
}

/** What you can do on the Reminders page. */
sealed interface RemindersEvent {
    data class SetEnabled(val ruleId: String, val enabled: Boolean) : RemindersEvent

    /** [deltaMinutes] is added to the interval, kept within 5 min … 4 h. */
    data class ChangeInterval(val ruleId: String, val deltaMinutes: Int) : RemindersEvent

    data class SetStrictness(val ruleId: String, val strictness: Strictness) : RemindersEvent

    data class DeleteOneOff(val id: Long) : RemindersEvent
}

/**
 * State holder for the Reminders page (docs/UI-PATTERN.md). [state] updates by itself when a reminder fires,
 * when one is added elsewhere (Quick Add, voice), and after each [onEvent].
 *
 * Threads: runs on [scope]'s dispatcher (the UI thread in the app). Database writes go to `env.dispatchers.io`;
 * [ReminderEngine] calls stay on [scope]'s thread, because the engine is not thread-safe and its own loop runs there.
 */
class RemindersStateHolder(
    private val reminders: ReminderRepository,
    private val engine: ReminderEngine,
    private val memory: MemoryRepository,
    private val env: AppEnv,
    private val scope: CoroutineScope,
) {
    /** Bumped after a change that no other flow reports (a rule edited here). */
    private val refresh = MutableStateFlow(0)

    val state: StateFlow<RemindersUiState> =
        combine(refresh, engine.active, reminders.pendingOneOffsFlow(env.dispatchers.io)) { _, _, _ -> build() }
            .stateIn(scope, SharingStarted.Eagerly, build())

    fun onEvent(e: RemindersEvent) {
        scope.launch {
            withContext(env.dispatchers.io) {
                when (e) {
                    is RemindersEvent.SetEnabled -> updateRule(e.ruleId) { it.copy(enabled = e.enabled) }

                    is RemindersEvent.ChangeInterval -> updateRule(e.ruleId) {
                        it.copy(interval = (it.interval + e.deltaMinutes).coerceIn(MIN, MAX))
                    }

                    is RemindersEvent.SetStrictness -> updateRule(e.ruleId) { it.copy(strictness = e.strictness) }

                    is RemindersEvent.DeleteOneOff -> reminders.deleteOneOff(e.id, env.millis())
                }
            }
            engine.tick()
            refresh.value++
        }
    }

    private data class RuleEdit(val interval: Int, val strictness: Strictness, val enabled: Boolean)

    private fun updateRule(id: String, change: (RuleEdit) -> RuleEdit) {
        val r = reminders.rules().firstOrNull { it.id == id } ?: return
        val edit = change(RuleEdit(r.intervalMinutes, r.strictness, r.enabled))
        reminders.updateRule(id, edit.interval, edit.strictness, edit.enabled)
    }

    private fun build(): RemindersUiState {
        val now = env.millis()
        return RemindersUiState(
            rules = reminders.rules().map {
                RemindersUiState.RuleRow(it.id, it.title, "every ${formatMinutes(it.intervalMinutes)}", it.strictness, it.enabled)
            },
            oneOffs = reminders.pendingOneOffs().map { RemindersUiState.OneOffRow(it.id, it.title, due(it.dueAt), it.uid) },
            upcoming = engine.upcoming(8).filter { it.key.startsWith("rule:") }.map {
                val overdue = it.dueAt <= now
                RemindersUiState.UpcomingRow(it.key, it.title, if (overdue) "Due now" else due(it.dueAt), overdue)
            },
            learnedQuiet = memory.byKey(MemoryEngine.KEY_QUIET)?.text,
        )
    }

    private fun due(at: Long): String = Instant.ofEpochMilli(at).atZone(env.zone()).format(DUE_FORMAT)

    private companion object {
        const val MIN = 5
        const val MAX = 240
        val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a")
    }
}
