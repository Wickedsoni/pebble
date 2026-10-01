package dev.pebble.core.brain

/**
 * Pebble's action vocabulary and how the model's MASSIVE intents map onto it.
 * Must stay in sync with brain/src/pebble_brain/pebble_intents.py (same table).
 */
object PebbleActions {
    const val REMIND = "remind"
    const val REMINDERS_QUERY = "reminders_query"
    const val REMINDER_REMOVE = "reminder_remove"
    const val ADD_NOTE = "add_note"
    const val NOTES_QUERY = "notes_query"
    const val NOTE_REMOVE = "note_remove"
    const val TIME_QUERY = "time_query"
    const val CHITCHAT = "chitchat"
    const val OTHER = "other"

    private val massiveToPebble = mapOf(
        "calendar_set" to REMIND,
        "alarm_set" to REMIND,
        "calendar_query" to REMINDERS_QUERY,
        "alarm_query" to REMINDERS_QUERY,
        "calendar_remove" to REMINDER_REMOVE,
        "alarm_remove" to REMINDER_REMOVE,
        "lists_createoradd" to ADD_NOTE,
        "lists_query" to NOTES_QUERY,
        "lists_remove" to NOTE_REMOVE,
        "datetime_query" to TIME_QUERY,
        "general_greet" to CHITCHAT,
        "general_joke" to CHITCHAT,
        "general_quirky" to CHITCHAT,
    )

    fun fromMassive(intent: String): String = massiveToPebble[intent] ?: OTHER
}
