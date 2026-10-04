package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path

/** What the "Model packs" card needs from the app; every call runs off the UI thread. */
interface ModelPacksPort {
    /** One line per model ("intent", "asr"), ready to show. */
    suspend fun models(): List<ModelPacksUiState.Row>

    /** Checks and stages the pack in [zip]; returns null when it is staged, or why it was refused. */
    suspend fun install(zip: Path): String?

    suspend fun remove(name: String)
}

/** The "Model packs" card on the About page, ready to draw. */
@Immutable
data class ModelPacksUiState(
    val rows: List<Row> = emptyList(),
    /** The result of the last install or removal; null before the first one. */
    val message: String? = null,
    val busy: Boolean = false,
) {
    /** [status]: "Built in", "Pack v3 (signed)", "Installs when Pebble starts again: v4", … */
    @Immutable
    data class Row(val name: String, val label: String, val status: String, val canRemove: Boolean)
}

sealed interface ModelPacksEvent {
    data class Install(val zip: Path) : ModelPacksEvent

    data class Remove(val name: String) : ModelPacksEvent
}

/** State holder for "Model packs" (docs/UI-PATTERN.md). Changes take effect the next time Pebble starts. */
class ModelPacksStateHolder(private val port: ModelPacksPort, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ModelPacksUiState())
    val state: StateFlow<ModelPacksUiState> = _state.asStateFlow()

    init {
        scope.launch { reload(null) }
    }

    fun onEvent(e: ModelPacksEvent) {
        _state.update { it.copy(busy = true) }
        when (e) {
            is ModelPacksEvent.Install -> scope.launch {
                val refused = port.install(e.zip)
                reload(if (refused == null) "Installed. Pebble uses it the next time it starts." else "Not installed: $refused")
            }

            is ModelPacksEvent.Remove -> scope.launch {
                port.remove(e.name)
                reload("Removed. Pebble uses the built-in model the next time it starts.")
            }
        }
    }

    private suspend fun reload(message: String?) {
        val rows = port.models()
        _state.update { it.copy(rows = rows, message = message ?: it.message, busy = false) }
    }
}
