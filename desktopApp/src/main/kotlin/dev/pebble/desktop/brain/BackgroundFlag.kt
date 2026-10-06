package dev.pebble.desktop.brain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A yes/no answer that needs file work (for example "is a signed chat pack installed?"), kept for the UI thread.
 * [value] only reads a field and is false until [refresh] ran. Call [refresh] off the UI thread: at start-up,
 * and after anything that can change the answer.
 */
class BackgroundFlag(private val compute: () -> Boolean) {
    private val _state = MutableStateFlow(false)

    /** The answer as a flow, for the UI. */
    val state: StateFlow<Boolean> = _state.asStateFlow()

    val value: Boolean get() = _state.value

    /** How many times [refresh] ran (tests: "no file work" means zero). */
    @Volatile
    var refreshes: Int = 0
        private set

    /** Does the file work now, on the calling thread. A failure counts as "no". */
    fun refresh() {
        refreshes++
        _state.value = runCatching(compute).getOrDefault(false)
    }
}
