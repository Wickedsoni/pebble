package dev.pebble.core

import dev.pebble.core.brain.Embedding
import dev.pebble.core.brain.Turn
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.search.BruteForceIndex
import dev.pebble.core.search.IndexedVector
import dev.pebble.core.search.MemorySearch
import dev.pebble.core.search.VectorCodec
import dev.pebble.core.wellness.NoteRepository
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Memory search with a fake encoder (words hashed into a vector), so results are exact and repeatable. */
class MemorySearchTest {
    private val db = DatabaseFactory.inMemory()
    private val notes = NoteRepository(db)
    private val memory = dev.pebble.core.memory.MemoryRepository(db)
    private val conversation = dev.pebble.core.brain.ConversationRepository(db)
    private var version: String? = "fake-1"
    private var loaded = true
    private var embedded = 0

    /** Each word adds 1 to one of 64 slots; texts that share words point the same way. */
    private fun fake(text: String): Embedding? {
        if (!loaded) return null
        embedded++
        val v = FloatArray(64)
        Regex("""\w+""").findAll(text.lowercase()).forEach { v[(it.value.hashCode() and 0x7fffffff) % 64] += 1f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return Embedding(FloatArray(64) { v[it] / n })
    }

    private val search = MemorySearch(db, ::fake, { version }, clock = { 1L })

    private fun count() = db.vectorsQueries.vectorCount().executeAsOne()

    @Test
    fun vectorsSurviveTheBlobRoundTrip() {
        val values = floatArrayOf(0f, -0f, 1f, -1.5f, 3.4028235e38f, 1.4e-45f, Float.NaN, 0.123456789f)
        assertContentEquals(values.map { it.toRawBits() }, VectorCodec.decode(VectorCodec.encode(values)).map { it.toRawBits() })
        assertEquals(32, VectorCodec.encode(values).size, "float32: 4 bytes each")
        assertEquals(
            0x3F800000,
            VectorCodec.encode(floatArrayOf(1f)).let {
                (it[0].toInt() and 0xFF) or ((it[3].toInt() and 0xFF) shl 24) or
                    ((it[2].toInt() and 0xFF) shl 16)
            },
        )
    }

    @Test
    fun theIndexReturnsTheClosestFirst() {
        fun e(vararg v: Float) = Embedding(v)
        val index =
            BruteForceIndex(
                listOf(
                    IndexedVector("note", "a", e(1f, 0f)),
                    IndexedVector("note", "b", e(0.6f, 0.8f)),
                    IndexedVector("note", "c", e(0f, 1f)),
                ),
            )
        assertEquals(listOf("c", "b"), index.search(e(0f, 1f), k = 2).map { it.refId })
        assertEquals(listOf("a", "b", "c"), index.search(e(1f, 0f), k = 10).map { it.refId })
    }

    @Test
    fun notesFactsAndWhatYouSaidAreIndexedOnce() {
        notes.add("buy milk and eggs", 1)
        memory.remember("my sister's birthday is 12 June", 1)
        conversation.add(Turn(1, "remind me to call the plumber", "typed", "Remind me", "OK"))
        assertEquals(3, search.reconcile())
        assertEquals(3L, count())
        assertEquals(0, search.reconcile(), "nothing new: nothing embedded again")
    }

    @Test
    fun anEditedNoteIsEmbeddedAgainAndADeletedOneIsRemoved() {
        val id = notes.add("buy milk", 1)
        val other = notes.add("call plumber", 1)
        search.reconcile()
        notes.update(id, "buy milk and bread", 2)
        notes.delete(other)
        assertEquals(2, search.reconcile(), "one re-embedded, one removed")
        assertEquals(1L, count())
        assertEquals("buy milk and bread", search.search("bread").single().text)
    }

    @Test
    fun aNewModelEmbedsEverythingAgain() {
        notes.add("buy milk", 1)
        notes.add("call plumber", 1)
        search.reconcile()
        version = "fake-2"
        assertEquals(2, search.reconcile())
    }

    @Test
    fun withoutAModelNothingIsIndexedAndSearchIsEmpty() {
        notes.add("buy milk", 1)
        loaded = false
        assertEquals(0, search.reconcile())
        assertTrue(search.search("milk").isEmpty())
        version = null
        assertEquals(0, search.reconcile())
    }

    @Test
    fun theRightItemComesFirst() {
        notes.add("project presentation slides", 1)
        notes.add("buy milk and eggs", 1)
        memory.remember("wifi password is pebble123", 1)
        search.reconcile()
        val hits = search.search("wifi password")
        assertEquals("wifi password is pebble123", hits.first().text)
        assertEquals(MemorySearch.FACT, hits.first().kind)
        assertTrue(hits.none { it.text == "buy milk and eggs" }, "no shared words, no meaning: below the bar")
    }

    @Test
    fun aForgottenFactIsNeverFoundEvenBeforeTheIndexIsCleaned() {
        memory.remember("my passport is in the drawer", 1)
        search.reconcile()
        assertEquals(1, search.search("passport").size)
        memory.forget(memory.visible().single())
        assertTrue(search.search("passport").isEmpty(), "read back from the source: gone means gone")
        assertEquals(1, search.reconcile(), "and the next pass removes its vector")
        assertEquals(0L, count())
    }

    @Test
    fun theTopicOfAQuestion() {
        assertEquals("the project", MemorySearch.topicOf("what did I note about the project?"))
        assertEquals("my sister", MemorySearch.topicOf("anything related to my sister"))
        assertEquals("project", MemorySearch.topicOf("project ke baare mein kya likha tha"))
        assertEquals("प्रोजेक्ट", MemorySearch.topicOf("प्रोजेक्ट के बारे में क्या लिखा था"))
        assertNull(MemorySearch.topicOf("show my notes"))
        assertNull(MemorySearch.topicOf("read my shopping list"))
        assertNull(MemorySearch.topicOf("what's this about"), "a topic made only of stop words is no topic")
    }

    @Test
    fun aStaleIndexDoesNotHideAGoodHitBehindDeletedCandidates() {
        val gone = listOf("dentist visit tuesday one", "dentist visit tuesday two", "dentist visit tuesday three")
            .map { notes.add(it, 1) }
        notes.add("dentist visit", 1)
        search.reconcile()
        gone.forEach { notes.delete(it, 2) } // the index is now stale: its 3 best candidates are gone

        assertEquals(listOf("dentist visit"), search.search("dentist visit tuesday", limit = 1).map { it.text })
    }
}
