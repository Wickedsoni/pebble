package dev.pebble.desktop.brain

/**
 * What a build of several native objects has made so far. If a later step fails, [buildOrClose] frees the earlier
 * ones (newest first) before it throws again, so a failed model load leaks no native memory.
 */
internal class Cleanup {
    private val actions = ArrayDeque<() -> Unit>()

    /** Remembers [value] and returns it; [close] frees it. */
    fun <T> track(value: T, close: (T) -> Unit): T {
        actions.addFirst { close(value) }
        return value
    }

    internal fun closeAll(cause: Throwable) {
        actions.forEach { action -> runCatching(action).onFailure(cause::addSuppressed) }
        actions.clear()
    }
}

/** Runs [block]; if it throws, everything it tracked with [Cleanup.track] is freed first. */
internal inline fun <R> buildOrClose(block: (Cleanup) -> R): R {
    val cleanup = Cleanup()
    try {
        return block(cleanup)
    } catch (t: Throwable) {
        cleanup.closeAll(t)
        throw t
    }
}
