package dev.pebble.desktop.platform

import kotlin.concurrent.thread

/** What one wait for the "show Pebble" signal ended with. */
enum class SignalWait { SIGNALED, TIMEOUT, FAILED }

/**
 * Waits for the "show Pebble" signal that a second launch sends (no socket: a named Win32 event, see
 * [SingleInstance]). A daemon thread calls [await] with a short timeout in a loop, so [stop] ends it fast.
 * [onShow] runs on that thread: the caller moves it to the UI thread.
 *
 * @param await blocks for at most the given milliseconds
 * @param onShow called once for each signal
 * @param pollMillis the timeout that each [await] gets
 * @param onEnd called on the waiting thread when it ends (after [stop] or a failed wait)
 */
class ShowSignalWaiter(
    private val await: (timeoutMillis: Int) -> SignalWait,
    private val onShow: () -> Unit,
    private val pollMillis: Int = POLL_MILLIS,
    private val onEnd: () -> Unit = {},
) {
    @Volatile private var stopped = false
    private var worker: Thread? = null

    /** Starts the waiting thread. A second call does nothing. */
    @Synchronized
    fun start() {
        if (worker != null) return
        worker = thread(isDaemon = true, name = "pebble-show-signal") {
            try {
                while (!stopped) {
                    when (await(pollMillis)) {
                        SignalWait.SIGNALED -> if (!stopped) runCatching(onShow)
                        SignalWait.TIMEOUT -> Unit
                        SignalWait.FAILED -> return@thread
                    }
                }
            } finally {
                onEnd()
            }
        }
    }

    /** Asks the thread to end and waits for it (about one poll at most). [onEnd] has run when this returns. */
    fun stop() {
        stopped = true
        val w = synchronized(this) { worker }
        if (w != null && w != Thread.currentThread()) w.join(pollMillis + JOIN_SLACK_MILLIS)
    }

    private companion object {
        const val POLL_MILLIS = 500
        const val JOIN_SLACK_MILLIS = 1_000L
    }
}
