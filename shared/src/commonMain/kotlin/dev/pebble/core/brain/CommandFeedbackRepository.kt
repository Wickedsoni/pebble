package dev.pebble.core.brain

import dev.pebble.db.PebbleDatabase

data class CommandFeedback(
    val text: String,
    val chosenAction: String,
    val modelIntent: String?,
    val modelConfidence: Double?,
    val atMillis: Long,
    val outcome: String,
)

/**
 * The learning loop's labels, so the next model learns your phrasing:
 *  - [PICKED]: your "Did you mean…?" choice
 *  - [CONFIRMED]: Pebble acted on the model's guess and you didn't object
 *  - [WRONG]: you tapped "Not what I meant" ([markWrong] on the confirmed row)
 */
class CommandFeedbackRepository(private val db: PebbleDatabase) {
    private val q get() = db.brainQueries

    /** Returns the row id, so a later "Not what I meant" can flip it to [WRONG]. */
    fun record(text: String, chosenAction: String, understood: Understood?, at: Long, outcome: String = PICKED): Long =
        db.transactionWithResult {
            q.insertFeedback(text, chosenAction, understood?.top?.intent, understood?.top?.confidence?.toDouble(), at, outcome)
            q.lastFeedbackId().executeAsOne()
        }

    fun markWrong(id: Long) = q.setOutcome(WRONG, id)

    fun all(): List<CommandFeedback> = q.allFeedback().executeAsList().map {
        CommandFeedback(it.text, it.chosen_action, it.model_intent, it.model_confidence, it.at_millis, it.outcome)
    }

    fun count(): Long = q.feedbackCount().executeAsOne()

    companion object {
        const val PICKED = "picked"
        const val CONFIRMED = "confirmed"
        const val WRONG = "wrong"
    }
}
