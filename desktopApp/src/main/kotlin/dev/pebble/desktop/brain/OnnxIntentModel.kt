package dev.pebble.desktop.brain

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.pebble.core.brain.IntentGuess
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

    init {
        System.setProperty("offline", "true") // DJL: never try to download a native library
        tokenizer = HuggingFaceTokenizer.newInstance(
            dir.resolve("tokenizer/tokenizer.json"),
            mapOf("addSpecialTokens" to "true", "truncation" to "true", "maxLength" to "64", "padding" to "false"),
        )
        val labels = Json.parseToJsonElement(Files.readString(dir.resolve("labels.json"))).jsonObject
        intents = labels.getValue("intents").jsonArray.map { it.jsonPrimitive.content }
        tags = labels.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(dir.resolve("intent.int8.onnx").toString(), opts)
    }

    /** Token ids exactly as fed to the model — exposed for the parity test. */
    fun tokenIds(text: String): LongArray = encode(words(text)).ids

    override fun understand(text: String): Understood? {
        val words = words(text)
        if (words.isEmpty()) return null
        val enc = encode(words)
        val ids = enc.ids
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape).use { maskT ->
                session.run(mapOf("input_ids" to idT, "attention_mask" to maskT)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val intentLogits = (out[0].value as Array<FloatArray>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val slotLogits = (out[1].value as Array<Array<FloatArray>>)[0]
                    val probs = softmax(intentLogits)
                    val guesses = probs.indices.sortedByDescending { probs[it] }.take(3).map { IntentGuess(intents[it], probs[it]) }
                    // First sub-token of each real word (word id 0 is the "query:" prefix).
                    val firstPiece = IntArray(words.size) { -1 }
                    enc.wordIds.forEachIndexed { pos, wid ->
                        if (wid >= 1 && wid <= words.size && firstPiece[(wid - 1).toInt()] == -1) firstPiece[(wid - 1).toInt()] = pos
                    }
                    val wordTags = firstPiece.map { pos -> if (pos < 0) "O" else tags[argmax(slotLogits[pos])] }
                    return Understood(words, guesses, wordTags)
                }
            }
        }
    }

    private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun encode(words: List<String>) = tokenizer.encode((listOf("query:") + words).toTypedArray())

    override fun close() {
        session.close()
        tokenizer.close()
    }

    private fun softmax(x: FloatArray): FloatArray {
        val m = x.max()
        val e = FloatArray(x.size) { exp((x[it] - m).toDouble()).toFloat() }
        val s = e.sum()
        return FloatArray(e.size) { e[it] / s }
    }

    private fun argmax(x: FloatArray): Int = x.indices.maxBy { x[it] }
}
