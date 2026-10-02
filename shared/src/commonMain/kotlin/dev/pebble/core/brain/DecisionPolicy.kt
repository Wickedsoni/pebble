package dev.pebble.core.brain

import dev.pebble.core.brain.PebbleActions as A

/**
 * Act or ask? Decides on calibrated *action* probabilities ([Understood.actions]), so "0.8" means the
 * model is right about 80% of the time at that confidence (brain/calibrate.py).
 *
 * The bar depends on what a mistake costs: deleting something you wanted is worse than a wrong joke,
 * and asking "Did you mean…" is always cheaper than acting wrongly. Two rules:
 *  - the top action must reach its own [thresholds] value
 *  - and beat the runner-up by [margin]: a 0.55 / 0.40 split means the model is torn, so ask
 */
class DecisionPolicy(
    private val thresholds: Map<String, Float> = DEFAULT_THRESHOLDS,
    private val fallback: Float = 0.6f,
    private val margin: Float = 0.15f,
) {
    sealed interface Decision {
        data class Act(val guess: ActionGuess) : Decision
        data object Ask : Decision
    }

    fun decide(u: Understood): Decision {
        val ranked = u.actions
        val top = ranked.firstOrNull() ?: return Decision.Ask
        val second = ranked.getOrNull(1)?.confidence ?: 0f
        val bar = thresholds[top.action] ?: fallback
        return if (top.confidence >= bar && top.confidence - second >= margin) Decision.Act(top) else Decision.Ask
    }

    companion object {
        val DEFAULT_THRESHOLDS = mapOf(
            A.REMINDER_REMOVE to 0.9f, // destructive
            A.NOTE_REMOVE to 0.9f,
            A.REMIND to 0.7f, // a wrong reminder is noise you have to clean up
            A.ADD_NOTE to 0.6f,
            A.REMINDERS_QUERY to 0.6f, // read-only: a wrong answer costs a glance
            A.NOTES_QUERY to 0.6f,
            A.TIME_QUERY to 0.6f,
            A.CHITCHAT to 0.5f,
            A.OTHER to 0.7f, // "can't do that yet" is unhelpful if we misread a real command
        )
    }
}
