package dev.pebble.desktop

import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.CommandRouter.Source
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What you say is answered and kept: the conversation and the live reminder list both show it. */
class ConversationTest {
    private val app = PebbleApp(DatabaseFactory.inMemory())

    @Test
    fun everyExchangeIsRemembered() {
        app.converse(
            "tum bahut cute ho",
            "voice",
            Routed.Run(QuickCommand.Chitchat("tum bahut cute ho", "general_quirky"), Source.MODEL, null, "chitchat"),
        ) {
                _,
                _,
            ->
        }
        app.converse("note: buy milk", "typed", Routed.Run(QuickCommand.AddNote("buy milk"), Source.RULES)) { _, _ -> }
        val turns = app.conversation.recent()
        assertEquals(listOf("tum bahut cute ho" to "voice", "note: buy milk" to "typed"), turns.map { it.said to it.via })
        assertTrue(turns[0].reply.isNotBlank(), "a chat reply is shown, not just a pet bubble")
        assertEquals("Saved to your notes.", turns[1].reply)
        assertEquals(1, app.notes.recent().size)
    }

    @Test
    fun aVoiceReminderAppearsInTheLiveList() = runBlocking {
        app.converse(
            "Remind me around 7 o'clock.",
            "voice",
            Routed.Run(QuickCommand.RemindAt("Reminder", 19, 0, null), Source.MODEL, null, "remind"),
        ) {
                _,
                _,
            ->
        }
        val live = app.reminders.pendingOneOffsFlow().first()
        assertEquals(listOf("Reminder"), live.map { it.title })
        assertTrue(app.conversation.recent().single().reply.startsWith("I'll remind you"))
    }

    @Test
    fun choosingFromDidYouMeanIsAlsoATurn() {
        val option = CommandRouter.Option("Save as note", "add_note", QuickCommand.AddNote("kal ka plan"))
        app.converseChoice("kal ka plan", "typed", option, null)
        assertEquals("picked", app.commandFeedback.all().single().outcome)
        assertEquals("kal ka plan", app.conversation.recent().single().said)
    }
}
