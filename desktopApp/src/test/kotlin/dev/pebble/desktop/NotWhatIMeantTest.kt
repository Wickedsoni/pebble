package dev.pebble.desktop

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.CommandRouter.Source
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The learning loop's undo: "Not what I meant" takes back what Pebble did and labels it wrong. */
class NotWhatIMeantTest {
    private val text = "ek note bana lo project ka topic"
    private val understood = Understood(text.split(" "), listOf(IntentGuess("lists_createoradd", 0.8f)), List(7) { "O" })

    @Test
    fun undoesTheNoteAndLabelsItWrong() {
        val app = PebbleApp(DatabaseFactory.inMemory())
        var retried: Pair<String, String>? = null
        val line = app.executeFromModel(text, Routed.Run(QuickCommand.AddNote(text), Source.MODEL, understood, "add_note")) { t, a ->
            retried = t to a
        }
        assertEquals(1, app.notes.recent().size)
        assertEquals(CommandFeedbackRepository.CONFIRMED, app.commandFeedback.all().single().outcome)

        line.actions.single { it.label == "Not what I meant" }.onClick()

        assertTrue(app.notes.recent().isEmpty(), "the note Pebble made by mistake is gone")
        val fb = app.commandFeedback.all().single()
        assertEquals(CommandFeedbackRepository.WRONG to "add_note", fb.outcome to fb.chosenAction)
        assertEquals(text to "add_note", retried)
    }

    @Test
    fun undoesAReminder() {
        val app = PebbleApp(DatabaseFactory.inMemory())
        val line = app.executeFromModel(text, Routed.Run(QuickCommand.RemindIn("Chai", 30), Source.MODEL, understood, "remind")) { _, _ -> }
        assertEquals(1, app.reminders.pendingOneOffs().size)
        line.actions.single().onClick()
        assertTrue(app.reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun rulesAndQueriesGetNoUndoButtonUnlessTheModelChose() {
        val app = PebbleApp(DatabaseFactory.inMemory())
        // A rule-based command has no model action, so nothing to call wrong.
        val line = app.executeFromModel(text, Routed.Run(QuickCommand.TellTime, Source.RULES), { _, _ -> })
        assertTrue(line.actions.isEmpty())
        assertTrue(app.commandFeedback.all().isEmpty())
    }
}
