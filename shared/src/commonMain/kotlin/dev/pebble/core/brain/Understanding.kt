package dev.pebble.core.brain

/** One intent the model considered, with its probability. */
data class IntentGuess(val intent: String, val confidence: Float)

/** The mood head's reading of a sentence: low | neutral | good, calibrated. */
data class MoodGuess(val mood: String, val confidence: Float) {
    val isLow: Boolean get() = mood == "low"
}

/** A Pebble action with the summed probability of every intent that maps to it, plus its best intent. */
data class ActionGuess(val action: String, val confidence: Float, val bestIntent: String)

/**
 * What the command model made of a line of text: ranked intent guesses (all of them, calibrated)
 * plus a slot tag per word (BIO tags such as `B-time`, `I-date`, `O`), aligned with [words].
 */
data class Understood(
    val words: List<String>,
    val guesses: List<IntentGuess>,
    val tags: List<String>,
    /** Null for models without a mood head. */
    val mood: MoodGuess? = null,
    /** The sentence as a unit-length vector, for search and the personal layer; null for models before v3. */
    val embedding: Embedding? = null,
) {
    val top: IntentGuess get() = guesses.first()

    /**
     * Pebble actions ranked by summed probability: alarm_set + calendar_set both count for "remind".
     * This is what decisions use — the top intent alone understates how sure the model is of the action.
     * "other" is not one action but many unrelated ones (music, weather, news…), so those intents are
     * never summed: each stays its own guess, or 47 small leftovers would add up to false confidence.
     */
    val actions: List<ActionGuess> by lazy {
        guesses.groupBy { actionGroupOf(it.intent) }
            .map { (_, gs) ->
                val best = gs.maxBy { it.confidence }
                ActionGuess(PebbleActions.fromMassive(best.intent), gs.sumOf { it.confidence.toDouble() }.toFloat(), best.intent)
            }
            .sortedByDescending { it.confidence }
    }

    /** Slot name → its words joined, e.g. `time` → "5 baje", `date` → "kal". First span wins. */
    fun slots(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var kind: String? = null
        val buf = StringBuilder()
        fun flush() {
            kind?.let { out.putIfAbsent(it, buf.toString()) }
            kind = null
            buf.clear()
        }
        words.zip(tags).forEach { (w, t) ->
            when {
                t.startsWith("B-") -> { flush(); kind = t.drop(2); buf.append(w) }
                t.startsWith("I-") && t.drop(2) == kind -> buf.append(' ').append(w)
                else -> flush()
            }
        }
        flush()
        return out
    }
}

/**
 * The key that groups intents into one action: the Pebble action of [intent], or `other:<intent>` for the intents
 * that map to "other" (music, weather, news…), so each of those stays its own group and never adds up.
 */
internal fun actionGroupOf(intent: String): String =
    PebbleActions.fromMassive(intent).let { if (it == PebbleActions.OTHER) "other:$intent" else it }

/** Anything that can read a command: the local ONNX model on desktop, a stub in tests. */
fun interface Understanding {
    /** Returns null when no model is available (missing file, failed load) — callers fall back to rules. */
    fun understand(text: String): Understood?
}

/**
 * A sentence embedding (mean-pooled encoder output, L2-normalised). Its own class, not a raw `FloatArray`
 * in [Understood]: arrays compare by reference, which would break `Understood`'s data-class equality.
 */
class Embedding(val values: FloatArray) {
    val size: Int get() = values.size

    /** Cosine similarity; both vectors are unit length, so this is the dot product. */
    fun cosine(other: Embedding): Float {
        require(other.size == size) { "embeddings of different size: $size vs ${other.size}" }
        var s = 0f
        for (i in values.indices) s += values[i] * other.values[i]
        return s
    }

    override fun equals(other: Any?): Boolean = other is Embedding && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "Embedding(${values.size})"
}
