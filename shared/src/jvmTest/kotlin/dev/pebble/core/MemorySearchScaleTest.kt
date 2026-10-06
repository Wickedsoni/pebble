package dev.pebble.core

import dev.pebble.core.brain.Embedding
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.search.BruteForceIndex
import dev.pebble.core.search.IndexedVector
import dev.pebble.core.search.MemorySearch
import dev.pebble.core.search.VectorCodec
import dev.pebble.core.wellness.NoteRepository
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Search over a few thousand items: same answers as the plain scan, and a timing printout. */
class MemorySearchScaleTest {
    private val db = DatabaseFactory.inMemory()
    private val notes = NoteRepository(db)
    private val memory = dev.pebble.core.memory.MemoryRepository(db)
    private val conversation = dev.pebble.core.brain.ConversationRepository(db)

    private fun fake(text: String): Embedding {
        val v = FloatArray(64)
        Regex("""\w+""").findAll(text.lowercase()).forEach { v[(it.value.hashCode() and 0x7fffffff) % 64] += 1f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return Embedding(FloatArray(64) { v[it] / n })
    }

    private val search = MemorySearch(db, ::fake, { "fake-1" }, clock = { 1L })
    private val vocabulary = Random(5).let { r -> List(300) { String(CharArray(7) { 'a' + r.nextInt(26) }) } }

    /** The scan as it was before the change: every vector, every source, every score, then sort. */
    private fun reference(query: String, limit: Int): List<Pair<String, Float>> {
        val items = db.vectorsQueries.vectorsOfVersion("fake-1").executeAsList()
            .map { IndexedVector(it.kind, it.ref_id, Embedding(VectorCodec.decode(it.vec))) }
        val current = search.sources().associateBy { it.kind to it.refId }
        val qv = fake(query)
        val asked = query.trim().lowercase()
        return BruteForceIndex(items).search(qv, items.size).mapNotNull { h ->
            val s = current[h.kind to h.refId] ?: return@mapNotNull null
            if (s.text.trim().lowercase() == asked) return@mapNotNull null
            s.text to (0.6f * h.score + 0.4f * MemorySearch.sharedWords(query, s.text))
        }.filter { it.second >= MemorySearch.MIN_SCORE }.sortedByDescending { it.second }.distinctBy { it.first.lowercase() }.take(limit)
    }

    @Test
    fun largeIndexGivesTheSameAnswersAsTheFullScan() {
        val random = Random(11)
        repeat(2_000) { i -> notes.add("note $i " + List(6) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" "), 1) }
        repeat(500) { i ->
            memory.remember(
                "fact $i " + List(5) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" "),
                1_000L + i,
            )
        }
        repeat(500) { i ->
            conversation.add(
                dev.pebble.core.brain.Turn(
                    1,
                    "said $i " + List(5) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" "),
                    "typed",
                    "x",
                    "OK",
                ),
            )
        }
        assertEquals(3_000, search.reconcile())
        notes.delete(db.vectorsQueries.sourceNotes().executeAsList().first().id) // deleted since it was indexed: must never come back

        val queries = List(40) { List(3) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" ") }
        var nonEmpty = 0
        for (q in queries) {
            val want = reference(q, 5)
            val got = search.search(q, 5).map { it.text to it.score }
            assertEquals(want.map { it.first }, got.map { it.first }, "same hits for '$q'")
            want.zip(got).forEach { (w, g) -> assertTrue(abs(w.second - g.second) < 1e-5f) }
            if (want.isNotEmpty()) nonEmpty++
        }
        assertTrue(nonEmpty >= 10, "the queries must find something, or the comparison proves little ($nonEmpty)")

        fun timed(block: (String) -> Unit): Double {
            repeat(40) { block(queries[it % queries.size]) } // warm up
            val t0 = System.nanoTime()
            repeat(100) { block(queries[it % queries.size]) }
            return (System.nanoTime() - t0) / 100 / 1e6
        }
        val before = timed { reference(it, 5) }
        val after = timed { search.search(it, 5) }
        println("MEMSEARCH-TIMING 3000 items: full scan ${"%.1f".format(before)} ms, search ${"%.1f".format(after)} ms per query")
    }
}
