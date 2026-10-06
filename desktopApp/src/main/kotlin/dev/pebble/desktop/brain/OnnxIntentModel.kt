package dev.pebble.desktop.brain

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.pebble.core.brain.Embedding
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.MoodGuess
import dev.pebble.core.brain.Understanding
import dev.pebble.core.brain.Understood
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp

/**
 * The M1 command model (trained in `brain/`): int8 ONNX encoder + intent and slot heads, run on
 * the CPU with ONNX Runtime. Tokenisation uses the same `tokenizer.json` as training, through
 * HuggingFace's tokenizers (DJL binding, native library bundled — nothing is downloaded).
 *
 * Mirrors `brain/src/pebble_brain/intent_model.py`: words split on whitespace, a leading "query:"
 * word (e5's prefix), slot tag read from each word's first sub-token.
 */
class OnnxIntentModel(dir: Path, threads: Int = 2) : Understanding, AutoCloseable {
    private val tokenizer: HuggingFaceTokenizer
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val intents: List<String>
    private val tags: List<String>

    /** Calibration temperature from labels.json (brain/calibrate.py); 1.0 for uncalibrated models. */
    private val temperature: Float

    /** Mood head labels and temperature; null for models trained before the mood head. */
    private val moods: List<String>?
    private val moodTemperature: Float

    init {
        System.setProperty("offline", "true") // DJL: never try to download a native library
        tokenizer = HuggingFaceTokenizer.newInstance(
            dir.resolve("tokenizer/tokenizer.json"),
            mapOf("addSpecialTokens" to "true", "truncation" to "true", "maxLength" to "64", "padding" to "false"),
        )
        try {
            val labels = Json.parseToJsonElement(Files.readString(dir.resolve("labels.json"))).jsonObject
            intents = labels.getValue("intents").jsonArray.map { it.jsonPrimitive.content }
            tags = labels.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
            temperature = labels["temperature"]?.jsonPrimitive?.content?.toFloat() ?: 1f
            moods = labels["moods"]?.jsonArray?.map { it.jsonPrimitive.content }
            moodTemperature = labels["mood_temperature"]?.jsonPrimitive?.content?.toFloat() ?: 1f
            // The options only build the session; their native object is freed as soon as it exists.
            session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(threads)
                opts.setInterOpNumThreads(1)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(dir.resolve("intent.int8.onnx").toString(), opts)
            }
        } catch (t: Throwable) {
            tokenizer.close() // a failed load must not leave the tokenizer's native memory behind
            throw t
        }
    }

    /** Token ids exactly as fed to the model — exposed for the parity test. */
    fun tokenIds(text: String): LongArray = encode(words(text)).ids

    /**
     * Slot tag probabilities for each word (tag name to probability) — exposed for the parity test, so it can
     * tell a real decoding bug from a near-tie that flips on another CPU's int8 kernels.
     */
    fun slotProbabilities(text: String): List<Map<String, Float>> {
        val words = words(text)
        if (words.isEmpty()) return emptyList()
        val enc = encode(words)
        val slotLogits = infer(enc).slots
        return firstPieces(words, enc).map { pos ->
            if (pos < 0) mapOf("O" to 1f) else softmax(slotLogits[pos]).withIndex().associate { (i, p) -> tags[i] to p }
        }
    }

    override fun understand(text: String): Understood? {
        val words = words(text)
        if (words.isEmpty()) return null
        val enc = encode(words)
        val raw = infer(enc)
        val probs = softmax(FloatArray(raw.intent.size) { raw.intent[it] / temperature })
        // All intents, so probabilities can be summed per Pebble action (Understood.actions).
        val guesses = probs.indices.sortedByDescending { probs[it] }.map { IntentGuess(intents[it], probs[it]) }
        val wordTags = firstPieces(words, enc).map { pos -> if (pos < 0) "O" else tags[argmax(raw.slots[pos])] }
        val mood = moods?.let { names ->
            raw.mood?.let { z ->
                val p = softmax(FloatArray(z.size) { z[it] / moodTemperature })
                val k = argmax(p)
                MoodGuess(names[k], p[k])
            }
        }
        return Understood(words, guesses, wordTags, mood, raw.embedding?.let(::Embedding))
    }

    /** Raw outputs for one sentence; [mood] / [embedding] are null for models exported before them. */
    private class Logits(val intent: FloatArray, val slots: Array<FloatArray>, val mood: FloatArray?, val embedding: FloatArray?)

    private fun infer(enc: Encoding): Logits {
        val ids = enc.ids
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape).use { maskT ->
                session.run(mapOf("input_ids" to idT, "attention_mask" to maskT)).use { out ->
                    // By export name (export_onnx.py); by position for a model exported without names.
                    fun output(name: String, index: Int): Any? = out.get(name).map { it.value }.orElse(null)
                        ?: if (index < out.size()) out[index].value else null

                    @Suppress("UNCHECKED_CAST")
                    val intent = (output("intent_logits", 0) as Array<FloatArray>)[0]

                    @Suppress("UNCHECKED_CAST")
                    val slots = (output("slot_logits", 1) as Array<Array<FloatArray>>)[0]

                    @Suppress("UNCHECKED_CAST")
                    val mood = (output("mood_logits", 2) as Array<FloatArray>?)?.get(0)

                    @Suppress("UNCHECKED_CAST")
                    val embedding = (output("embedding", 3) as Array<FloatArray>?)?.get(0)
                    return Logits(intent, slots, mood, embedding)
                }
            }
        }
    }

    /** Position of the first sub-token of each real word (word id 0 is the "query:" prefix); -1 if truncated away. */
    private fun firstPieces(words: List<String>, enc: Encoding): IntArray {
        val firstPiece = IntArray(words.size) { -1 }
        enc.wordIds.forEachIndexed { pos, wid ->
            if (wid >= 1 && wid <= words.size && firstPiece[(wid - 1).toInt()] == -1) firstPiece[(wid - 1).toInt()] = pos
        }
        return firstPiece
    }

    private fun words(text: String) = splitWords(text)

    private fun encode(words: List<String>) = tokenizer.encode((listOf("query:") + words).toTypedArray())

    override fun close() {
        session.close()
        tokenizer.close()
    }

    companion object {
        /**
         * Splits [text] into words like Python's `str.split()` in `intent_model.py`: on every Unicode white space
         * (no-break space, thin space, ideographic space, …), not only ASCII. Another split shifts the slot tags.
         */
        fun splitWords(text: String): List<String> {
            val words = mutableListOf<String>()
            var start = -1
            for (i in text.indices) {
                if (isSpace(text[i])) {
                    if (start >= 0) words += text.substring(start, i)
                    start = -1
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) words += text.substring(start)
            return words
        }

        /** Python's `str.isspace()` for one char: Unicode space, line and paragraph separators, controls 1C to 1F, and U+0085. */
        private fun isSpace(c: Char) = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'
    }

    private fun softmax(x: FloatArray): FloatArray {
        val m = x.max()
        val e = FloatArray(x.size) { exp((x[it] - m).toDouble()).toFloat() }
        val s = e.sum()
        return FloatArray(e.size) { e[it] / s }
    }

    private fun argmax(x: FloatArray): Int = x.indices.maxBy { x[it] }
}
