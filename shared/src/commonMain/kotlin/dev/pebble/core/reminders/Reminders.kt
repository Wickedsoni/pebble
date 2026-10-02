package dev.pebble.core.reminders

import kotlinx.serialization.Serializable

@Serializable
enum class ReminderKind(val emoji: String) {
    WATER("💧"),
    STRETCH("🙆"),
    EYES("👀"),
    CUSTOM("⏰"),
    ;

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.name == s } ?: CUSTOM
    }
}

/** How hard the pet pushes when you ignore a reminder. Chosen per reminder type. */
@Serializable
enum class Strictness(val label: String) {
    GENTLE("Gentle"),
    NORMAL("Normal"),
    STRICT("Strict coach"),
    ;

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.name == s } ?: NORMAL
    }
}

/** What the pet does right now for an overdue reminder. Stages only ever escalate. */
enum class Escalation { BUBBLE, BOUNCE, TOAST, FOLLOW }

object EscalationPolicy {
    fun stage(strictness: Strictness, overdueMillis: Long): Escalation {
        val s = overdueMillis / 1_000
        return when (strictness) {
            Strictness.GENTLE -> Escalation.BUBBLE

            Strictness.NORMAL -> when {
                s < 60 -> Escalation.BUBBLE
                s < 180 -> Escalation.BOUNCE
                else -> Escalation.TOAST
            }

            Strictness.STRICT -> when {
                s < 30 -> Escalation.BUBBLE
                s < 120 -> Escalation.BOUNCE
                else -> Escalation.FOLLOW
            }
        }
    }
}

/** A repeating reminder like "water every 45 min", only active between [activeFromMinute]..[activeToMinute]. */
data class ReminderRule(
    val id: String,
    val kind: ReminderKind,
    val title: String,
    val intervalMinutes: Int,
    val strictness: Strictness,
    val enabled: Boolean,
    val activeFromMinute: Int,
    val activeToMinute: Int,
    val lastDoneAt: Long?,
)

/** A reminder that is currently due and waiting for you to act. */
data class ActiveReminder(
    val key: String,
    val kind: ReminderKind,
    val title: String,
    val strictness: Strictness,
    val dueAt: Long,
)

@Serializable
enum class ReminderAction { DONE, SNOOZED, DISMISSED }

/** Built-in health reminders, created on first launch. */
val DefaultRules = listOf(
    ReminderRule("water", ReminderKind.WATER, "Sip some water", 60, Strictness.GENTLE, true, 8 * 60, 23 * 60, null),
    ReminderRule("stretch", ReminderKind.STRETCH, "Stand up & stretch", 90, Strictness.GENTLE, true, 8 * 60, 23 * 60, null),
    ReminderRule("eyes", ReminderKind.EYES, "Rest your eyes", 60, Strictness.GENTLE, true, 8 * 60, 23 * 60, null),
)
