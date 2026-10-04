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

    /** The items closest to [query] that pass [MIN_SCORE], best first. Empty without a model. */
    fun search(query: String, limit: Int = 5): List<Result> {
        val version = modelVersion() ?: return emptyList()
        val qv = embed(query) ?: return emptyList()
        val items = q.vectorsOfVersion(version).executeAsList().map {
            IndexedVector(it.kind, it.ref_id, Embedding(VectorCodec.decode(it.vec)))
        }
        val current = sources().associateBy { it.kind to it.refId }
        val asked = query.trim().lowercase()
        return BruteForceIndex(items).search(qv, items.size).mapNotNull { h ->
            val s = current[h.kind to h.refId] ?: return@mapNotNull null // deleted or forgotten since it was indexed
            if (s.text.trim().lowercase() == asked) return@mapNotNull null // the question itself, said earlier
            Result(s.kind, s.refId, s.text, MEANING * h.score + WORDS * sharedWords(query, s.text))
        }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }.distinctBy { it.text.lowercase() }.take(limit)
    }

    companion object {
        const val NOTE = "note"
        const val FACT = "fact"
        const val SAID = "said"

        private const val CHUNK = 64
        private const val MEANING = 0.6f
        private const val WORDS = 0.4f

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
        fun sharedWords(query: String, text: String): Float {
            val qw = words(query)
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
