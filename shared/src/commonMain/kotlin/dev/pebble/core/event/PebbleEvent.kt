package dev.pebble.core.event

import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
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

    @Serializable
    @SerialName("reminder_due")
    data class ReminderDue(val key: String, val kind: ReminderKind, val title: String, override val atMillis: Long) : PebbleEvent

    /** How you reacted to a reminder — the raw signal the brain later learns timing from. */
    @Serializable
    @SerialName("reminder_acted")
    data class ReminderActed(
        val key: String,
        val kind: ReminderKind,
        val action: ReminderAction,
        val snoozeMinutes: Int? = null,
        override val atMillis: Long,
    ) : PebbleEvent

    @Serializable
    @SerialName("water_logged")
    data class WaterLogged(val ml: Int, val todayTotalMl: Int, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("note_created")
    data class NoteCreated(val noteId: Long, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("quick_add")
    data class QuickAddUsed(val command: String, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("pet_interaction")
    data class PetInteraction(val kind: String, override val atMillis: Long) : PebbleEvent

    /** Logged at most once per clock hour while you're at the computer; the basis for active-hours learning. */
    @Serializable
    @SerialName("active_hour")
    data class ActiveHour(val hour: Int, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("note_completed")
    data class NoteCompleted(val noteId: Long, override val atMillis: Long) : PebbleEvent

    @Serializable
    @SerialName("mood_logged")
    data class MoodLogged(val score: Int, override val atMillis: Long) : PebbleEvent

    /**
     * The nudge policy's choice for a repeating reminder that came due, with the probability it had of
     * choosing that arm — logged so a new policy can later be evaluated offline on real history (IPS).
     */
    @Serializable
    @SerialName("nudge_decided")
    data class NudgeDecided(
        val key: String,
        val context: String,
        val arm: String,
        val propensity: Double,
        override val atMillis: Long,
    ) : PebbleEvent

    @Serializable
    @SerialName("fact_remembered")
    data class FactRemembered(val memoryKey: String, override val atMillis: Long) : PebbleEvent
}
