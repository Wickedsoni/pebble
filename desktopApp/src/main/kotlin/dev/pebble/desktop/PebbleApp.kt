package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.layout.WidgetLayoutRepository
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** App-wide object graph. Created once in `main`, shared by every window. */
class PebbleApp(db: PebbleDatabase) {
    val bus = EventBus()
    val layouts = WidgetLayoutRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Unconfined: events are written on the publisher's thread, so nothing is lost on exit.
        EventLogger(db).attach(bus, CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined))
        bus.publish(PebbleEvent.AppStarted(now()))
    }

    fun shutdown() {
        bus.publish(PebbleEvent.AppStopping(now()))
        scope.cancel()
    }

    companion object {
        fun create(): PebbleApp = PebbleApp(DatabaseFactory.create())
    }
}

fun now(): Long = System.currentTimeMillis()
