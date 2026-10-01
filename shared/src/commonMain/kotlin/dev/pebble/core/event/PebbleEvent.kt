package dev.pebble.core.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything that happens in Pebble is published as an event. Widgets, the pet and the
 * brain subscribe to the [EventBus]; the [EventLogger] persists them for later learning.
 */
@Serializable
sealed interface PebbleEvent {
    val atMillis: Long

    @Serializable
    @SerialName("app_started")
    data class AppStarted(override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("app_stopping")
    data class AppStopping(override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("widget_moved")
    data class WidgetMoved(val widgetId: String, val x: Int, val y: Int, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("widget_visibility")
    data class WidgetVisibilityChanged(val widgetId: String, val visible: Boolean, override val atMillis: Long) : PebbleEvent
}
