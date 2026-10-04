package dev.pebble.core

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.CommandRouter.Source
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.MoodGuess
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
    fun lowMoodGetsACaringReply() {
        // With no model (or the model reading small talk), a low mood always gets care, never a joke.
        val router = CommandRouter({ null })
        for (t in listOf(
            "aaj mood thoda off hai",
            "आज मेरा मूड ठीक नहीं है",
            "i'm feeling a bit low today",
            "padhai me mann nhi lag rha",
            "आज किसी काम में मन नहीं है",
        )) {
            val r = router.route(t) as Routed.Run
            assertEquals(QuickCommand.Chitchat(t, Replies.LOW_MOOD), r.command)
        }
        assertTrue(!Replies.isLowMood("my phone battery is low"))
        assertTrue(Replies.chitchat("aaj mood thoda off hai", Replies.LOW_MOOD).isNotBlank())
    }

    @Test
    fun weekdayReminderGetsItsDate() {
        val text = "friday wali meeting 5 baje yaad dilana"
        // The model tagged only the clock; the weekday still comes from the sentence.
        val tags = listOf("O", "O", "O", "B-time", "I-time", "O", "O")
        val r = CommandRouter({ model(text to u(text, tags, "calendar_set" to 0.9f)) }, today = { 3 }).route(text) as Routed.Run
        val cmd = r.command as QuickCommand.RemindAt
        assertEquals(5 to 2, cmd.hour to cmd.dayOffset)
        assertTrue(cmd.flexibleHalfDay)
    }

    @Test
    fun weekdayWithoutATimeOffersTimesOnThatDay() {
        val text = "shukravar wali meeting yaad dilana"
        val reading = u(text, listOf("B-date", "O", "O", "O", "O"), "calendar_set" to 0.9f)
        val r = CommandRouter({ model(text to reading) }, today = { 3 }).route(text) as Routed.Ask
        assertEquals(listOf("Friday 9 AM", "Friday 1 PM", "Friday 6 PM", "Tomorrow 8 PM"), r.options.map { it.label })
        assertEquals(2, (r.options.first().command as QuickCommand.RemindAt).dayOffset)
    }

    @Test
    fun englishRulesReadWeekdays() {
        val r = CommandRouter({ null }, today = { 3 }).route("call mom friday at 5pm") as Routed.Run
        assertEquals(QuickCommand.RemindAt("Call mom", 17, 0, 2), r.command)
        val next = CommandRouter({ null }, today = { 3 }).route("dentist next wednesday at 10am") as Routed.Run
        assertEquals(QuickCommand.RemindAt("Dentist", 10, 0, 7), next.command)
    }

    @Test
    fun askAfterNotWhatIMeantLeavesOutTheWrongAction() {
        val text = "ek note bana lo project ka topic"
        val reading = u(text, List(7) { "O" }, "lists_createoradd" to 0.8f, "calendar_set" to 0.15f, "general_quirky" to 0.05f)
        val r = CommandRouter({ model(text to reading) }).ask(text, exclude = "add_note")
        assertEquals(listOf("chitchat"), r.options.map { it.action }.filter { it == "add_note" || it == "chitchat" })
        assertTrue(r.options.none { it.action == "add_note" })
        // "remind" has no time in the sentence, so it can't be offered as a ready command; chitchat can.
        assertTrue(r.options.isNotEmpty())
        // No model: only "save as note" is left to offer.
        assertEquals(listOf("add_note"), CommandRouter({ null }).ask(text, exclude = "chitchat").options.map { it.action })
    }

    @Test
    fun feedbackOutcomes() {
        val repo = CommandFeedbackRepository(DatabaseFactory.inMemory())
        val id = repo.record("x", "add_note", null, at = 1, outcome = CommandFeedbackRepository.CONFIRMED)
        repo.record("y", "remind", null, at = 2)
        repo.markWrong(id)
        assertEquals(listOf("wrong", "picked"), repo.all().map { it.outcome })
    }

    @Test
    fun moodHeadCatchesLowMoodWithoutTheWordList() {
        val text = "sab kuch galat ho raha hai"
        val reading = Understood(text.split(" "), listOf(IntentGuess("general_quirky", 0.8f)), List(5) { "O" }, MoodGuess("low", 0.9f))
        val r = CommandRouter({ model(text to reading) }).route(text) as Routed.Run
        assertEquals(QuickCommand.Chitchat(text, Replies.LOW_MOOD), r.command)
        // Not sure enough about the mood: normal small talk.
        val unsure = reading.copy(mood = MoodGuess("low", 0.55f))
        assertEquals(
            "general_quirky",
            (
                (
                    CommandRouter({
                        model(text to unsure)
                    }).route(text) as Routed.Run
                    ).command as QuickCommand.Chitchat
                ).intent,
        )
    }

    @Test
    fun aRealCommandStillRunsWhenTheMoodIsLow() {
        val text = "tension hai kal 7 baje padhai yaad dila dena"
        val tags = listOf("O", "O", "B-date", "B-time", "I-time", "O", "O", "O", "O")
        val reading = Understood(text.split(" "), listOf(IntentGuess("calendar_set", 0.9f)), tags, MoodGuess("low", 0.95f))
        val r = CommandRouter({ model(text to reading) }).route(text) as Routed.Run
        assertIs<QuickCommand.RemindAt>(r.command)
    }

    @Test
    fun aReminderWithOnlyATimeGetsAPlainTitle() {
        // Heard by voice in the first real install: the title came out as "Around".
        val text = "Remind me around 7 o'clock."
        val reading = u(text, List(5) { "O" }, "calendar_set" to 0.88f)
        val cmd = (CommandRouter({ model(text to reading) }).route(text) as Routed.Run).command as QuickCommand.RemindAt
        assertEquals("Reminder" to 7, cmd.title to cmd.hour)
    }

    @Test
    fun openReminderOpensThePageInsteadOfAskingWhen() {
        // The model reads "reminder" and would ask "When should I remind you: “Open”?"; the rule wins first.
        val text = "open reminder"
        val m = model(text to u(text, listOf("O", "O"), "calendar_set" to 0.9f))
        val router = CommandRouter({ m })
        val r = assertIs<Routed.Run>(router.route(text))
        assertEquals(QuickCommand.OpenPage("reminders", text), r.command)
        assertEquals(Source.RULES, r.source)
    }

    @Test
    fun repliesForOpeningMirrorTheScript() {
        assertEquals("Opening Reminders.", Replies.openPage("reminders", "open reminders", forRemoval = false))
        assertEquals("Notes khol raha hoon.", Replies.openPage("notes", "notes kholo", forRemoval = false))
        assertEquals("रिमाइंडर खोल रहा हूँ।", Replies.openPage("reminders", "रिमाइंडर खोलो", forRemoval = false))
        assertEquals(
            "Here are your reminders — remove the reminder you want.",
            Replies.openPage("reminders", "delete my reminder", forRemoval = true),
        )
    }

    @Test
    fun aNotesQueryThatNamesATopicSearches() {
        fun route(text: String): QuickCommand {
            val m = model(text to u(text, List(text.split(" ").size) { "O" }, "lists_query" to 0.95f))
            return assertIs<Routed.Run>(CommandRouter({ m }).route(text)).command
        }
        assertEquals(
            QuickCommand.SearchMemory("the project", "what did I note about the project"),
            route("what did I note about the project"),
        )
        assertEquals(
            QuickCommand.SearchMemory("project", "project ke baare mein kya likha tha"),
            route("project ke baare mein kya likha tha"),
        )
        assertEquals(QuickCommand.ShowNotes, route("read my shopping list"), "no topic named: the latest notes, as before")
        assertEquals(QuickCommand.ShowNotes, route("meri notes dikhao"))
    }
}
