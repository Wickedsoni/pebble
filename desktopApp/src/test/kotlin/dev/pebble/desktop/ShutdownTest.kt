package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.PebbleEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Quitting Pebble keeps "nothing lost on exit": every event published before shutdown is in event_log. */
class ShutdownTest {
    @Test
    fun everyEventBeforeQuitIsSaved() {
        val db = DatabaseFactory.inMemory()
        val app = PebbleApp(db)
        repeat(500) { app.bus.publish(PebbleEvent.PetInteraction("click", it.toLong())) }
        app.shutdown()
        val types = db.pebbleQueries.recentEvents(Long.MAX_VALUE).executeAsList().map { it.type }
        assertEquals(500, types.count { it == "pet_interaction" })
        assertEquals(1, types.count { it == "app_started" })
        assertEquals(1, types.count { it == "app_stopping" })
    }

    /** `shutdown()` is called by the tray Quit and by the JVM shutdown hook: a second call must change nothing. */
    @Test
    fun aSecondAndAConcurrentShutdownDoNothing() {
        val db = DatabaseFactory.inMemory()
        val app = PebbleApp(db)
        val threads = List(4) { Thread { app.shutdown() } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        app.shutdown()
        val types = db.pebbleQueries.recentEvents(Long.MAX_VALUE).executeAsList().map { it.type }
        assertEquals(1, types.count { it == "app_stopping" })
    }
}
