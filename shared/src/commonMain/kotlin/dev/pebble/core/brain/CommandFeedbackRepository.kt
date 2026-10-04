package dev.pebble.core.brain

import dev.pebble.db.Command_feedback
import dev.pebble.db.PebbleDatabase

data class CommandFeedback(
    val text: String,
    val chosenAction: String,
    val modelIntent: String?,
    val modelConfidence: Double?,
    val atMillis: Long,
    val outcome: String,
    val id: Long = 0,
)

/**
 * The learning loop's labels, so the next model learns your phrasing:
 *  - [PICKED]: your "Did you mean…?" choice
 *  - [CONFIRMED]: Pebble acted on the model's guess and you didn't object
 *  - [WRONG]: you tapped "Not what I meant" ([markWrong] on the confirmed row)
 *  - [TAUGHT]: a phrase you taught on the Memory page ("Teach Pebble a command")
 * The personal layer ([PersonalLayer]) uses taught, picked and wrong rows at once; retraining uses them later.
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

    fun all(): List<CommandFeedback> = q.allFeedback().executeAsList().map { it.toFeedback() }

    fun count(): Long = q.feedbackCount().executeAsOne()

    /** Every row from [since] on, oldest first: the personal layer's examples and context. */
    fun personalSince(since: Long): List<CommandFeedback> = q.personalFeedbackSince(since).executeAsList().map { it.toFeedback() }

    /** Saves [text] as a taught example of [action]. */
    fun teach(text: String, action: String, at: Long): Long = record(text, action, null, at, TAUGHT)

    fun taught(): List<CommandFeedback> = q.taughtFeedback().executeAsList().map { it.toFeedback() }

    fun delete(id: Long) = q.deleteFeedback(id)

    /** Deletes every taught example. */
    fun forgetTaught() = q.deleteTaught()

    private fun Command_feedback.toFeedback() = CommandFeedback(text, chosen_action, model_intent, model_confidence, at_millis, outcome, id)

    companion object {
        const val PICKED = "picked"
        const val CONFIRMED = "confirmed"
        const val WRONG = "wrong"
        const val TAUGHT = "taught"
    }
}
