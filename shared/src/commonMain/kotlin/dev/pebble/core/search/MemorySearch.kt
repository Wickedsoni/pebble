package dev.pebble.core.search

import dev.pebble.core.brain.Embedding
import dev.pebble.db.PebbleDatabase

/**
 * Search over what you wrote and said: notes, facts you told Pebble ("remember …"), and your side of the chat.
 *
 * Ranking = 0.6 × meaning (cosine of the command model's sentence embeddings) + 0.4 × shared words.
 * The command model was trained to tell commands apart, not topics, so meaning alone ranks loosely
 * (13/16 on brain/eval/memory_search_v1.jsonl); shared words fix most of the rest (14/16).
 *
 * Privacy: every hit is read back from its source table, so a deleted note or a forgotten fact is never
 * shown, even before [reconcile] removes its vector.
 */
class MemorySearch(
    private val db: PebbleDatabase,
    /** The sentence embedding of a text; null when the command model isn't loaded. */
    private val embed: (String) -> Embedding?,
    /** Which model makes the vectors; vectors of another model are made again. Null: no model. */
    private val modelVersion: () -> String?,
    private val clock: () -> Long,
) {
    data class Source(val kind: String, val refId: String, val text: String)

    data class Result(val kind: String, val refId: String, val text: String, val score: Float)

    private val q get() = db.vectorsQueries

    /** Everything that can be found, with its current text. */
    fun sources(): List<Source> =
        q.sourceNotes().executeAsList().map { Source(NOTE, it.id.toString(), it.text) } +
            q.sourceFacts().executeAsList().map { Source(FACT, it.id.toString(), it.text) } +
            q.sourceSaid().executeAsList().map { Source(SAID, it.id.toString(), it.said) }

    /**
     * Brings the index up to date: embeds new and changed items, removes vectors whose item is gone.
     * Stops early (and continues next time) if the model goes away. Returns how many rows changed.
     */
    fun reconcile(): Int = synchronized(this) {
        val version = modelVersion() ?: return 0
        val sources = sources().associateBy { it.kind to it.refId }
        val have = q.vectorKeys().executeAsList().associateBy { it.kind to it.ref_id }
        var changed = 0
        val gone = have.keys - sources.keys
        if (gone.isNotEmpty()) {
            db.transaction { gone.forEach { (kind, ref) -> q.deleteVector(kind, ref) } }
            changed += gone.size
        }
        val todo = sources.values.filter { s ->
            have[s.kind to s.refId].let { it == null || it.text != s.text || it.model_version != version }
        }
        for (chunk in todo.chunked(CHUNK)) {
            // Embed first, then write the chunk in one short transaction: the event writer never waits long.
            val made = mutableListOf<Pair<Source, Embedding>>()
            for (s in chunk) made += s to (embed(s.text) ?: break)
            if (made.isNotEmpty()) {
                db.transaction {
                    made.forEach { (s, e) -> q.upsertVector(s.kind, s.refId, s.text, VectorCodec.encode(e.values), version, clock()) }
                }
            }
            changed += made.size
            if (made.size < chunk.size) break // the model went away: continue next time
        }
        changed
    }

    /** A vector that is a candidate for the answer, scored with its indexed text. */
    private class Candidate(val kind: String, val refId: String, val lowerText: String, val meaning: Float, val preScore: Float)

    /**
     * The items closest to [query] that pass [MIN_SCORE], best first. Empty without a model.
     *
     * One pass over the vectors keeps only the best [CANDIDATES_PER_RESULT] x [limit] by score (computed with the
     * indexed text); only those are read back from their source tables, and scored again with the current text.
     */
    fun search(query: String, limit: Int = 5): List<Result> {
        val version = modelVersion() ?: return emptyList()
        val qv = embed(query) ?: return emptyList()
        val queryWords = words(query)
        val asked = query.trim().lowercase()
        val keep = (limit * CANDIDATES_PER_RESULT).coerceAtLeast(1)
        val top = ArrayList<Candidate>(keep + 1) // best first
        // The mapper does the work row by row, so only the few best candidates stay in memory, not every vector.
        q.vectorsForSearch(version) { kind, refId, text, vec ->
            val v = Embedding(VectorCodec.decode(vec))
            if (v.size == qv.size) {
                val meaning = v.cosine(qv)
                val lower = text.lowercase()
                // Splitting a text into words is the costly part: skip it when even the best case cannot get in.
                val ceiling = MEANING * meaning + WORDS * possibleShare(queryWords, lower)
                if (top.size < keep || ceiling > top.last().preScore) {
                    val pre = MEANING * meaning + WORDS * sharedWords(queryWords, text)
                    if (top.size < keep ||
                        pre > top.last().preScore
                    ) {
                        offer(top, keep, Candidate(kind, refId, lower, meaning, pre), text, asked)
                    }
                }
            }
        }.executeAsList()
        if (top.isEmpty()) return emptyList()
        val current = currentTexts(top)
        return top.mapNotNull { c ->
            val text = current[c.kind to c.refId] ?: return@mapNotNull null // deleted or forgotten since it was indexed
            if (text.trim().lowercase() == asked) return@mapNotNull null // the question itself, said earlier
            Result(c.kind, c.refId, text, MEANING * c.meaning + WORDS * sharedWords(queryWords, text))
        }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }.distinctBy { it.text.lowercase() }.take(limit)
    }

    /** Adds [c] to [top] (best first, at most [keep]) unless it is the question itself or a worse copy of a text already there. */
    private fun offer(top: MutableList<Candidate>, keep: Int, c: Candidate, text: String, asked: String) {
        if (text.trim().lowercase() == asked) return
        val same = top.indexOfFirst { it.lowerText == c.lowerText }
        if (same >= 0) {
            if (top[same].preScore >= c.preScore) return
            top.removeAt(same)
        }
        val at = top.indexOfFirst { it.preScore < c.preScore }.let { if (it < 0) top.size else it }
        top.add(at, c)
        if (top.size > keep) top.removeAt(top.lastIndex)
    }

    /** The current text of each candidate, read from its source table; a deleted or forgotten item is missing. */
    private fun currentTexts(candidates: List<Candidate>): Map<Pair<String, String>, String> {
        fun ids(kind: String) = candidates.filter { it.kind == kind }.mapNotNull { it.refId.toLongOrNull() }
        return q.sourcesByIds(ids(NOTE), ids(FACT), ids(SAID)).executeAsList().associate { (it.kind to it.id.toString()) to it.text }
    }

    companion object {
        const val NOTE = "note"
        const val FACT = "fact"
        const val SAID = "said"

        private const val CHUNK = 64
        private const val MEANING = 0.6f
        private const val WORDS = 0.4f

        /** How many best vectors per wanted result are read back: room for copies of one text and for stale index text. */
        private const val CANDIDATES_PER_RESULT = 3

        /** Below this, a hit is more likely noise than an answer (brain/eval/memory_search_v1.jsonl). */
        const val MIN_SCORE = 0.40f

        /** Words that say nothing about the topic, in all three scripts. */
        private val stopWords = setOf(
            "the", "a", "an", "my", "me", "i", "to", "of", "and", "on", "in", "for", "is", "are", "was", "it", "this", "that", "about",
            "ka", "ki", "ke", "ko", "hai", "h", "mein", "me", "se", "aur", "kya", "tha", "thi", "mera", "meri", "mere",
            "का", "की", "के", "को", "है", "में", "से", "और", "क्या", "था", "थी", "मेरा", "मेरी", "मेरे",
        )

        private val wordRx = Regex("""[\p{L}\p{M}\p{N}]+""")

        private fun words(t: String): Set<String> =
            wordRx.findAll(t.lowercase()).map { it.value }.filter { it.length >= 2 && it !in stopWords }.toSet()

        /** Share of the query's topic words that also appear in [text] (0 … 1). */
        fun sharedWords(query: String, text: String): Float = sharedWords(words(query), text)

        /** The most [sharedWords] can be for this lower-case text: query words that appear in it at all, even inside a word. */
        private fun possibleShare(qw: Set<String>, lowerText: String): Float =
            if (qw.isEmpty()) 0f else qw.count { lowerText.contains(it) }.toFloat() / qw.size

        /** Same, with the query's words made once. */
        private fun sharedWords(qw: Set<String>, text: String): Float {
            if (qw.isEmpty()) return 0f
            val tw = words(text)
            return qw.count { it in tw }.toFloat() / qw.size
        }

        private val topicRx = listOf(
            Regex("""\b(?:about|regarding|related\s+to)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:.*?\b(?:kya|kuch)\s+)?(.+?)\s+ke\s+(?:baare|bare|baarey)\s+(?:mein|me|main|men)\b.*$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+के\s+बारे\s+में.*$"""),
        )

        /**
         * The topic of a question that names one: "what did I note about the project" → "the project",
         * "project ke baare mein kya likha tha" → "project". Null if the sentence names no topic.
         */
        fun topicOf(text: String): String? = topicRx.firstNotNullOfOrNull { rx ->
            rx.find(text.trim().trimEnd('?', '.', '!', '।'))?.groupValues?.get(1)?.trim()?.takeIf { words(it).isNotEmpty() }
        }
    }
}
