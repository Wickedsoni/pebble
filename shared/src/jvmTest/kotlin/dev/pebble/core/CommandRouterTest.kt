package dev.pebble.core

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.CommandRouter.Source
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.Replies
import dev.pebble.core.brain.Understanding
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandRouterTest {
    /** A fake model: returns the canned reading for a sentence. */
    private fun model(vararg readings: Pair<String, Understood>) = Understanding { text -> readings.toMap()[text] }

    private fun u(text: String, tags: List<String>, vararg guesses: Pair<String, Float>) =
        Understood(text.split(" "), guesses.map { IntentGuess(it.first, it.second) }, tags)

    @Test
    fun rulesWinBeforeTheModelIsAsked() {
        var asked = false
        val router = CommandRouter({ asked = true; null })
        val r = router.route("water every 45m")
        assertIs<Routed.Run>(r)
        assertEquals(Source.RULES, r.source)
        assertTrue(!asked, "model must not run when a rule matches")
    }

    @Test
    fun noModelFallsBackToANote() {
        val r = CommandRouter({ null }).route("kal DBMS ka assignment hai")
        assertEquals(Routed.Run(QuickCommand.AddNote("kal DBMS ka assignment hai"), Source.FALLBACK), r)
    }

    @Test
    fun confidentHinglishReminderBecomesATimedReminder() {
        val text = "kal shaam 7 baje mummy ko call karne ki yaad dila dena"
        val reading = u(
            text,
            listOf("B-date", "B-timeofday", "B-time", "I-time", "O", "O", "O", "O", "O", "O", "O", "O"),
            "calendar_set" to 0.93f,
        )
        val r = CommandRouter({ model(text to reading) }).route(text) as Routed.Run
        assertEquals(Source.MODEL, r.source)
        val cmd = r.command as QuickCommand.RemindAt
        assertEquals(19, cmd.hour); assertEquals(0, cmd.minute); assertEquals(1, cmd.dayOffset)
        assertEquals("Mummy call karne", cmd.title)
    }

    @Test
    fun reminderWithoutATimeAsksWhen() {
        val text = "mujhe dawai lene ki yaad dilana"
        val reading = u(text, List(5) { "O" }, "calendar_set" to 0.9f)
        val r = CommandRouter({ model(text to reading) }).route(text)
        assertIs<Routed.Ask>(r)
        assertTrue(r.question.startsWith("When should I remind you"))
        assertEquals(4, r.options.size)
    }

    @Test
    fun unsureModelAsksDidYouMeanWithANoteOption() {
        val text = "tum bahut cute ho"
        val reading = u(text, List(4) { "O" }, "general_quirky" to 0.41f, "music_likeness" to 0.3f, "lists_createoradd" to 0.1f)
        val r = CommandRouter({ model(text to reading) }).route(text) as Routed.Ask
        assertEquals(listOf("chitchat", "add_note"), r.options.map { it.action })
    }

    @Test
    fun queriesAndUnsupportedIntentsMapCleanly() {
        val router = CommandRouter({
            model(
                "abhi kitne baje hain" to u("abhi kitne baje hain", List(4) { "O" }, "datetime_query" to 0.95f),
                "koi gaana chalao" to u("koi gaana chalao", List(3) { "O" }, "play_music" to 0.97f),
            )
        })
        assertEquals(QuickCommand.TellTime, (router.route("abhi kitne baje hain") as Routed.Run).command)
        assertIs<QuickCommand.Unsupported>((router.route("koi gaana chalao") as Routed.Run).command)
    }

    @Test
    fun feedbackIsStored() {
        val repo = CommandFeedbackRepository(DatabaseFactory.inMemory())
        val reading = u("tum bahut cute ho", List(4) { "O" }, "general_quirky" to 0.41f)
        repo.record("tum bahut cute ho", "chitchat", reading, at = 5)
        val saved = repo.all().single()
        assertEquals("chitchat", saved.chosenAction)
        assertEquals("general_quirky", saved.modelIntent)
    }

    @Test
    fun timeComesFromTheSentenceWhenTheSlotHasNoClock() {
        val text = "shaam 7 baje mummy ko call karne ki yaad dila dena"
        // The model tagged only "shaam" as a time and "mummy" as a person.
        val tags = listOf("B-timeofday", "O", "O", "B-person", "O", "O", "O", "O", "O", "O")
        val r = CommandRouter({ model(text to u(text, tags, "calendar_set" to 0.9f)) }).route(text) as Routed.Run
        val cmd = r.command as QuickCommand.RemindAt
        assertEquals(19, cmd.hour)
        assertEquals("Mummy call karne", cmd.title)
    }

    @Test
    fun emptyTitleFallsBackToALabelNotTheSentence() {
        val text = "कल सुबह छह बजे मुझे जगा देना"
        val tags = listOf("B-date", "B-timeofday", "B-time", "I-time", "O", "O", "O")
        val r = CommandRouter({ model(text to u(text, tags, "alarm_set" to 0.9f)) }).route(text) as Routed.Run
        val cmd = r.command as QuickCommand.RemindAt
        assertEquals("Wake up", cmd.title)
        assertEquals(6 to 1, cmd.hour to cmd.dayOffset)
    }

    @Test
    fun lowMoodGetsACaringReplyBeforeTheModel() {
        var asked = false
        val router = CommandRouter({ asked = true; null })
        for (t in listOf("aaj mood thoda off hai", "आज मेरा मूड ठीक नहीं है", "i'm feeling a bit low today")) {
            val r = router.route(t) as Routed.Run
            assertEquals(QuickCommand.Chitchat(t, Replies.LOW_MOOD), r.command)
        }
        assertTrue(!asked)
        assertTrue(!Replies.isLowMood("my phone battery is low"))
        assertTrue(Replies.chitchat("aaj mood thoda off hai", Replies.LOW_MOOD).isNotBlank())
    }
}
