package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.growth.Growth
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The "Growth" card of the Companion page, ready to draw. */
@Immutable
data class CompanionUiState(
    /** Null until the first count is done (it reads the whole history, so it is never done on the UI thread). */
    val growth: Growth? = null,
)

/** What you can do on the Companion page. */
sealed interface CompanionEvent {
    /** The pet may have grown: count again. */
    data object Refresh : CompanionEvent
}

/**
 * State holder for the growth of the Companion page (docs/UI-PATTERN.md). [loadGrowth] reads the whole history, so it
 * must switch to a background thread itself (the page gives it `withContext`). A new [CompanionEvent.Refresh] replaces
 * a count that still runs: the newest count wins. The page sends the first [CompanionEvent.Refresh]. A failure is logged and keeps the last good state.
 */
class CompanionStateHolder(
    private val loadGrowth: suspend () -> Growth,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.None,
) {
    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()

    private var counting: Job? = null

    fun onEvent(e: CompanionEvent) {
        when (e) {
            CompanionEvent.Refresh -> {
                counting?.cancel()
                counting = scope.launch {
                    try {
                        _state.value = CompanionUiState(growth = loadGrowth())
                    } catch (c: CancellationException) {
                        throw c
                    } catch (x: Exception) {
                        log.warn("Companion", "growth count failed", x)
                    }
                }
            }
        }
    }
}
