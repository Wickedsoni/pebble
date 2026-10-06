package dev.pebble.desktop.pet

import dev.pebble.core.reminders.Escalation
import dev.pebble.core.reminders.EscalationPolicy
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.Strictness
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.core.UiPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The part of the pet's job that must not depend on the pet window: it runs for the whole life of the app.
 *
 * - **Toasts.** With the companion hidden, every due reminder becomes a Windows notification (once per due
 *   time). With the companion shown, the speech bubble carries the reminder and a toast only follows the
 *   `TOAST` escalation step (for the first due reminder, as before).
 * - **Waiting.** While a fullscreen app or a video is in front, due reminders wait 5 minutes with no reaction
 *   counted. A gentle reminder nobody answered in 3 minutes steps aside for 30 minutes.
 * - **Growth.** Every 3 minutes it reads the pet's earned level off the main thread and reports it.
 *
 * [run] and [tick] must be called on the main thread: [ReminderEngine] is not thread-safe. Only [readEarned]
 * runs on [work]; its result comes back to the main thread through [onEarned].
 */
class ReminderPresenter(
    private val engine: ReminderEngine,
    private val ui: UiPort,
    private val env: AppEnv,
    /** Whether the companion window is shown ("Show companion"). */
    private val petVisible: () -> Boolean,
    /** Whether a fullscreen app or a video is in front. Cheap; called every 2 s. */
    private val quiet: () -> Boolean,
    /** The level the pet has earned. It reads the database, so it runs on [work]. */
    private val readEarned: () -> Stage,
    /** A level was read. Called on the main thread. */
    private val onEarned: (Stage) -> Unit,
    /** One thread for slow reads (growth). */
    private val work: CoroutineDispatcher,
    private val log: Logger = Logger.None,
) {
    private val toasted = mutableSetOf<String>()
    private var quietNow = false
    private var lastQuietPoll = Long.MIN_VALUE

    /** Presents reminders and checks growth until the calling coroutine is cancelled. */
    suspend fun run() = coroutineScope {
        launch {
            while (isActive) {
                delay(GROWTH_EVERY_MS)
                checkGrowth()
            }
        }
        while (isActive) {
            tick()
            delay(TICK_MS)
        }
    }

    /** How many toasts are remembered (for tests). */
    internal fun toastedCount(): Int = toasted.size

    /** One pass over the due reminders. Main thread only. */
    fun tick() {
        val now = env.millis()
        if (lastQuietPoll == Long.MIN_VALUE || now - lastQuietPoll >= QUIET_POLL_MS || now < lastQuietPoll) {
            lastQuietPoll = now
            quietNow = runCatching(quiet).getOrDefault(false)
        }
        val active = engine.active.value
        // A reminder that is no longer due may come back later with a new due time: forget its toast.
        toasted.retainAll(active.map { it.key + it.dueAt }.toSet())
        if (quietNow) {
            // Don't pop up over a movie or a fullscreen app: due reminders wait (no reaction counted).
            active.forEach { engine.defer(it.key, minutes = QUIET_DEFER_MINUTES) }
            return
        }
        val visible = petVisible()
        active.forEachIndexed { index, r ->
            val overdue = now - r.dueAt
            val toast = if (visible) {
                index == 0 && EscalationPolicy.stage(r.strictness, overdue) == Escalation.TOAST
            } else {
                true
            }
            if (toast && toasted.add(r.key + r.dueAt)) ui.notify("Pebble", r.title)
            // With the pet shown, only the reminder its bubble shows steps aside (as before); hidden, each one does.
            if ((!visible || index == 0) && r.strictness == Strictness.GENTLE && overdue > GENTLE_GIVE_UP_MS) {
                engine.defer(r.key, minutes = GENTLE_DEFER_MINUTES)
            }
        }
    }

    private suspend fun checkGrowth() {
        val earned = try {
            withContext(work) { readEarned() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "growth check failed", e)
            return
        }
        onEarned(earned)
    }

    private companion object {
        const val TAG = "presenter"
        const val TICK_MS = 1_000L
        const val QUIET_POLL_MS = 2_000L
        const val GROWTH_EVERY_MS = 180_000L
        const val QUIET_DEFER_MINUTES = 5
        const val GENTLE_GIVE_UP_MS = 3 * 60_000L
        const val GENTLE_DEFER_MINUTES = 30
    }
}
