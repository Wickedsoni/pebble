package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.layout.WidgetLayoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    private val db = DatabaseFactory.inMemory()

    @Test
    fun layoutPositionIsUpsertedAndVisibilityKept() {
        val repo = WidgetLayoutRepository(db)
        assertNull(repo.get("clock"))

        repo.setVisible("clock", false)
        assertFalse(repo.get("clock")!!.hasPosition)

        repo.savePosition("clock", 100, 200)
        repo.savePosition("clock", 300, 400)

        val layout = repo.get("clock")!!
        assertEquals(300, layout.x)
        assertEquals(400, layout.y)
        assertFalse(layout.visible)
    }

    @Test
    fun loggerStoresEventTypeAndPayload() {
        EventLogger(db).log(PebbleEvent.WidgetMoved("clock", 1, 2, atMillis = 42))

        val row = db.pebbleQueries.recentEvents(1).executeAsOne()
        assertEquals("widget_moved", row.type)
        assertEquals(42, row.at_millis)
        assertTrue(row.payload.contains("\"widgetId\":\"clock\""))
    }
}
