package dev.pebble.core.brain

import dev.pebble.db.PebbleDatabase

data class CommandFeedback(val text: String, val chosenAction: String, val modelIntent: String?, val modelConfidence: Double?, val atMillis: Long)

/** Stores your answers to "Did you mean…?" so the next model can learn your phrasing. */
class CommandFeedbackRepository(private val db: PebbleDatabase) {
    private val q get() = db.brainQueries

    fun record(text: String, chosenAction: String, understood: Understood?, at: Long) {
        q.insertFeedback(text, chosenAction, understood?.top?.intent, understood?.top?.confidence?.toDouble(), at)
    }

    fun all(): List<CommandFeedback> = q.allFeedback().executeAsList().map {
        CommandFeedback(it.text, it.chosen_action, it.model_intent, it.model_confidence, it.at_millis)
    }

    fun count(): Long = q.feedbackCount().executeAsOne()
}
