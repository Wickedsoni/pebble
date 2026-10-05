package dev.pebble.core

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.Embedding
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.PersonalLayer
import dev.pebble.core.brain.Understanding
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.pebble.core.brain.PebbleActions as A

class PersonalLayerTest {
    private val feedback = CommandFeedbackRepository(DatabaseFactory.inMemory())
    private var version: String? = "v3"
    private var loaded = true
    private var embedded = 0
    private var hour = 20

    /** Unit vectors at a known angle from "north": cos(angle) is the similarity to [NORTH]. */
    private val vectors = HashMap<String, Embedding>()

    private fun at(cosToNorth: Float): Embedding {
        val a = kotlin.math.acos(cosToNorth)
        return Embedding(floatArrayOf(cos(a), sin(a), 0f))
    }

    private val layer = PersonalLayer(
        source = { feedback.personalSince(0) },
        embed = { t -> if (!loaded) null else (vectors[t] ?: Embedding(floatArrayOf(0f, 0f, 1f))).also { embedded++ } },
        modelVersion = { version },
        hourOf = { hour },
        clock = { 0L },
    )

    /** The model's (wrong) reading: a reminder, sure enough to act. */
    private fun reading(text: String, e: Embedding? = NORTH) = Understood(
        text.split(" "),
        listOf(
            IntentGuess("calendar_set", 0.8f),
            IntentGuess("lists_query", 0.1f),
            IntentGuess("lists_remove", 0.05f),
            IntentGuess("weather_query", 0.05f),
        ),
        List(text.split(" ").size) { "O" },
        embedding = e,
    )

    private fun router(vararg texts: String, e: Embedding? = NORTH) =
        CommandRouter({ Understanding { t -> if (t in texts) reading(t, e) else null } }, personal = layer)

    @Test
    fun withoutExamplesTheReadingIsUnchanged() {
        layer.refresh()
        val u = reading("notes kholo yaar")
        assertSame(u, layer.adjust("notes kholo yaar", u))
    }

    @Test
    fun aTaughtSentenceActsOnTheTaughtAction() {
        feedback.teach("Notes  kholo yaar", A.NOTES_QUERY, 1)
        layer.refresh()
        val r = router("notes kholo yaar").route("notes kholo yaar")
        assertIs<Routed.Run>(r)
        assertEquals(QuickCommand.ShowNotes, r.command)
        assertEquals(A.NOTES_QUERY, r.action)
    }

    @Test
    fun notWhatIMeantAloneMakesPebbleAskNextTime() {
        val id = feedback.record("chai break le lo", A.REMIND, null, 1, CommandFeedbackRepository.CONFIRMED)
        feedback.markWrong(id)
        layer.refresh()
        val u = layer.adjust("chai break le lo", reading("chai break le lo"))
        assertTrue(u.actions.sumOf { it.confidence.toDouble() } < 0.99, "a veto removes mass; it is not given to others")
        assertIs<Routed.Ask>(router("chai break le lo").route("chai break le lo"))
    }

    @Test
    fun aPickAfterNotWhatIMeantWins() {
        val id = feedback.record("chai break le lo", A.REMIND, null, 1, CommandFeedbackRepository.CONFIRMED)
        feedback.markWrong(id)
        feedback.record("chai break le lo", A.NOTES_QUERY, null, 2)
        layer.refresh()
        val r = router("chai break le lo").route("chai break le lo")
        assertIs<Routed.Run>(r)
        assertEquals(A.NOTES_QUERY, r.action)
    }

    @Test
    fun confirmedRowsAreNotExamples() {
        feedback.record("notes kholo yaar", A.NOTES_QUERY, null, 1, CommandFeedbackRepository.CONFIRMED)
        layer.refresh()
        assertEquals(0, layer.size)
    }

    @Test
    fun aNeighbourCountsOnlyIfCloseAndSharingWords() {
        vectors["notes kholo yaar"] = at(0.98f) // close to every query here
        vectors["notes kholo please"] = at(0.85f) // shares words, too far
        feedback.teach("notes kholo yaar", A.NOTES_QUERY, 1)
        layer.refresh()
        val near = layer.adjust("notes kholo abhi", reading("notes kholo abhi"))
        val notes = near.actions.first { it.action == A.NOTES_QUERY }.confidence
        assertTrue(notes > 0.3f, "one close neighbour that shares words moves the reading: $notes")
        assertIs<Routed.Ask>(router("notes kholo abhi").route("notes kholo abhi"), "but one neighbour is not enough to act")

        val noCommonWord = reading("meri list dekho")
        assertSame(noCommonWord, layer.adjust("meri list dekho", noCommonWord), "close in meaning, no common word: no effect")

        feedback.forgetTaught()
        feedback.teach("notes kholo please", A.NOTES_QUERY, 2)
        layer.refresh()
        val far = reading("notes kholo abhi")
        assertSame(far, layer.adjust("notes kholo abhi", far), "below τ: no effect")
    }

    @Test
    fun theLayerNeverLiftsARemovalToAct() {
        feedback.teach("list saaf karo", A.NOTE_REMOVE, 1)
        layer.refresh()
        val u = layer.adjust("list saaf karo", reading("list saaf karo"))
        assertEquals(A.NOTE_REMOVE, u.actions.first().action)
        assertTrue(u.actions.first().confidence <= PersonalLayer.REMOVAL_CAP + 1e-6f)
        assertIs<Routed.Ask>(router("list saaf karo").route("list saaf karo"))
    }

    @Test
    fun guessesStayRankedAndActionsFollowThem() {
        feedback.teach("notes kholo yaar", A.NOTES_QUERY, 1)
        layer.refresh()
        val u = layer.adjust("notes kholo yaar", reading("notes kholo yaar"))
        assertEquals(u.guesses.sortedByDescending { it.confidence }, u.guesses)
        assertEquals(u.top.intent, u.actions.first().bestIntent)
    }

    @Test
    fun refreshEmbedsOnlyNewSentencesAndRedoesThemForANewModel() {
        feedback.teach("notes kholo yaar", A.NOTES_QUERY, 1)
        assertEquals(1, layer.refresh())
        feedback.teach("list dikhao", A.NOTES_QUERY, 2)
        assertEquals(1, layer.refresh(), "only the new sentence")
        version = "v4"
        assertEquals(2, layer.refresh(), "a new model: all again")
    }

    @Test
    fun theSameSentenceCountsEvenWithoutTheModel() {
        loaded = false
        feedback.teach("notes kholo yaar", A.NOTES_QUERY, 1)
        assertEquals(0, layer.refresh())
        assertEquals(0, embedded)
        assertEquals(A.NOTES_QUERY, layer.adjust("notes kholo yaar", reading("notes kholo yaar")).actions.first().action)
    }

    @Test
    fun whatYouUsuallyPickAtThisHourComesFirstInDidYouMean() {
        val torn = Understood(
            listOf("kal", "ka", "kaam"),
            listOf(IntentGuess("calendar_query", 0.4f), IntentGuess("lists_query", 0.35f), IntentGuess("weather_query", 0.25f)),
            listOf("O", "O", "O"),
        )
        val r = CommandRouter({ Understanding { torn } }, personal = layer)
        assertEquals(A.REMINDERS_QUERY, (r.route("kal ka kaam") as Routed.Ask).options.first().action)
        repeat(4) { i -> feedback.record("something else $i", A.NOTES_QUERY, null, i.toLong()) }
        layer.refresh()
        assertEquals(A.NOTES_QUERY, (r.route("kal ka kaam") as Routed.Ask).options.first().action, "4 evening picks of notes")
        hour = 9
        assertEquals(A.REMINDERS_QUERY, (r.route("kal ka kaam") as Routed.Ask).options.first().action, "no picks in the morning")
    }

    private companion object {
        val NORTH = Embedding(floatArrayOf(1f, 0f, 0f))
    }
}
