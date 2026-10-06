package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.search.MemorySearch
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "Search memory" card on the Memory page, ready to draw. */
@Immutable
data class MemorySearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    /** Null before the first search; empty when nothing matched. */
    val results: List<Row>? = null,
    /** The command model is missing, so there is nothing to search with. */
    val noModel: Boolean = false,
    /** The search failed (database or model error). Text that is ready to show; null when all is well. */
    val error: String? = null,
) {
    @Immutable
    data class Row(val kindLabel: String, val text: String)
}

sealed interface MemorySearchEvent {
    data class Search(val query: String) : MemorySearchEvent

    data object Clear : MemorySearchEvent
}

/**
 * State holder for memory search (docs/UI-PATTERN.md). [search] loads the model if needed, catches up the
 * index and searches, off the UI thread; it returns null when there is no command model at all.
 */
class MemorySearchStateHolder(
    private val search: suspend (String) -> List<MemorySearch.Result>?,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.None,
) {
    private val _state = MutableStateFlow(MemorySearchUiState())
    val state: StateFlow<MemorySearchUiState> = _state.asStateFlow()
    private var running: Job? = null

    fun onEvent(e: MemorySearchEvent) {
        when (e) {
            is MemorySearchEvent.Search -> {
                val query = e.query.trim().takeIf { it.isNotEmpty() } ?: return
                running?.cancel() // a newer question wins
                _state.update { it.copy(query = query, searching = true) }
                running = scope.launch {
                    val outcome = runCatching { search(query) }
                    outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    _state.value = outcome.fold(
                        onSuccess = { hits ->
                            MemorySearchUiState(
                                query = query,
                                searching = false,
                                results = hits?.map { MemorySearchUiState.Row(kindLabel(it.kind), it.text) } ?: emptyList(),
                                noModel = hits == null,
                            )
                        },
                        onFailure = {
                            log.warn("memory-search", "search failed", it)
                            MemorySearchUiState(query = query, searching = false, error = "The search did not work. Try again.")
                        },
                    )
                }
            }

            MemorySearchEvent.Clear -> {
                running?.cancel()
                _state.value = MemorySearchUiState()
            }
        }
    }

    private fun kindLabel(kind: String) = when (kind) {
        MemorySearch.NOTE -> "Note"
        MemorySearch.FACT -> "You told me"
        MemorySearch.SAID -> "You said"
        else -> kind
    }
}
