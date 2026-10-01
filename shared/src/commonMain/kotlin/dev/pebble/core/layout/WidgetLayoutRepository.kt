package dev.pebble.core.layout

import dev.pebble.db.PebbleDatabase

/** A saved widget placement. [x]/[y] are -1 until the widget has been positioned once. */
data class WidgetLayout(val widgetId: String, val x: Int, val y: Int, val visible: Boolean) {
    val hasPosition: Boolean get() = x >= 0 && y >= 0
}

class WidgetLayoutRepository(private val db: PebbleDatabase) {
    private val queries get() = db.pebbleQueries

    fun get(widgetId: String): WidgetLayout? =
        queries.selectLayout(widgetId).executeAsOneOrNull()?.let {
            WidgetLayout(it.widget_id, it.x.toInt(), it.y.toInt(), it.visible != 0L)
        }

    fun savePosition(widgetId: String, x: Int, y: Int) {
        queries.upsertPosition(widgetId, x.toLong(), y.toLong())
    }

    fun setVisible(widgetId: String, visible: Boolean) {
        queries.upsertVisible(widgetId, if (visible) 1L else 0L)
    }
}
