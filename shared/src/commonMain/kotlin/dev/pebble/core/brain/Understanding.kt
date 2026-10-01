package dev.pebble.core.brain

/** One intent the model considered, with its probability. */
data class IntentGuess(val intent: String, val confidence: Float)

/**
 * What the command model made of a line of text: ranked intent guesses plus a slot tag per word
 * (BIO tags such as `B-time`, `I-date`, `O`), aligned with [words].
 */
data class Understood(
    val words: List<String>,
    val guesses: List<IntentGuess>,
    val tags: List<String>,
) {
    val top: IntentGuess get() = guesses.first()

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

/** Anything that can read a command: the local ONNX model on desktop, a stub in tests. */
fun interface Understanding {
    /** Returns null when no model is available (missing file, failed load) — callers fall back to rules. */
    fun understand(text: String): Understood?
}
