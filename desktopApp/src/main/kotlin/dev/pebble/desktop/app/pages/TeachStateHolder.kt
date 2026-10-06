package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.brain.CommandFeedback
import dev.pebble.core.brain.CommandRouter
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.pebble.core.brain.PebbleActions as A

/** What the "Teach Pebble a command" card needs from the app; every call runs off the UI thread. */
interface TeachingPort {
    /** Taught phrases, oldest first. */
    suspend fun taught(): List<CommandFeedback>

    suspend fun teach(phrase: String, action: String)

    suspend fun unteach(id: Long)

    /** Deletes every taught phrase, and the layer stops using your earlier picks. */
    suspend fun forgetAll()
}

/** The "Teach Pebble a command" card on the Memory page, ready to draw. */
@Immutable
data class TeachUiState(
    val action: String = TeachStateHolder.CHOICES.first(),
    val choices: List<Choice> = TeachStateHolder.CHOICES.map { Choice(it, CommandRouter.labelFor(it)) },
    val taught: List<Row> = emptyList(),
    /** The last change did not work (database error). Text that is ready to show; null when all is well. */
    val error: String? = null,
) {
    @Immutable
    data class Choice(val action: String, val label: String)

    @Immutable
    data class Row(val id: Long, val text: String, val actionLabel: String)
}

sealed interface TeachEvent {
    data class Choose(val action: String) : TeachEvent

    data class Teach(val phrase: String) : TeachEvent

    data class Unteach(val id: Long) : TeachEvent

    data object ForgetAll : TeachEvent
}

/** State holder for "Teach Pebble a command" (docs/UI-PATTERN.md). */
class TeachStateHolder(
    private val port: TeachingPort,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.None,
) {
    private val _state = MutableStateFlow(TeachUiState())
    val state: StateFlow<TeachUiState> = _state.asStateFlow()

    init {
        scope.launch { attempt("load") { } }
    }

    fun onEvent(e: TeachEvent) {
        when (e) {
            is TeachEvent.Choose -> if (e.action in CHOICES) _state.update { it.copy(action = e.action) }

            is TeachEvent.Teach -> {
                val phrase = e.phrase.trim().takeIf { it.isNotEmpty() } ?: return
                val action = _state.value.action
                scope.launch { attempt("teach") { port.teach(phrase, action) } }
            }

            is TeachEvent.Unteach -> scope.launch { attempt("unteach") { port.unteach(e.id) } }

            TeachEvent.ForgetAll -> scope.launch { attempt("forget") { port.forgetAll() } }
        }
    }

    /** Runs [change], then reloads the list. A failure is logged and shown; cancellation is not caught. */
    private suspend fun attempt(what: String, change: suspend () -> Unit) {
        val failure = runCatching {
            change()
            val rows = port.taught().map { TeachUiState.Row(it.id, it.text, CommandRouter.labelFor(it.chosenAction)) }
            _state.update { it.copy(taught = rows, error = null) }
        }.exceptionOrNull() ?: return
        if (failure is CancellationException) throw failure
        log.warn("teach", "$what failed", failure)
        _state.update { it.copy(error = "That did not work. Try again.") }
    }

    companion object {
        /** What you can teach. Removals are left out: the personal layer never makes Pebble remove without asking. */
        val CHOICES = listOf(A.REMIND, A.ADD_NOTE, A.NOTES_QUERY, A.REMINDERS_QUERY, A.TIME_QUERY, A.CHITCHAT)
    }
}
