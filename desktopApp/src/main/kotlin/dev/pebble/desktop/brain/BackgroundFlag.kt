package dev.pebble.desktop.brain

/**
 * A yes/no answer that needs file work (for example "is a signed chat pack installed?"), kept for the UI thread.
 * [value] only reads a field and is false until [refresh] ran. Call [refresh] off the UI thread: at start-up,
 * and after anything that can change the answer.
 */
class BackgroundFlag(private val compute: () -> Boolean) {
    @Volatile
    var value: Boolean = false
        private set

    /** Does the file work now, on the calling thread. A failure counts as "no". */
    fun refresh() {
        value = runCatching(compute).getOrDefault(false)
    }
}
