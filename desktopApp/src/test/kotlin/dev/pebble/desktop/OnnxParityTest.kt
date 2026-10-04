package dev.pebble.desktop

import dev.pebble.desktop.brain.OnnxIntentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin must read commands exactly like the Python pipeline did: same token ids, same intent,
 * same slot tags, near-identical confidence. Reference written by brain/ (parity.json).
 * Skipped when the model hasn't been built on this machine.
 */
class OnnxParityTest {
    private val dir: Path = ShippedModel.dir

    @Test
    fun kotlinMatchesPython() {
        // A local build writes parity.json next to the model; the released zip has no parity.json, so CI
        // uses the copy under test resources (named after the model folder, so a new model can't use a stale one).
        val local = dir.resolve("parity.json")
        val text = if (Files.exists(local)) {
            Files.readString(local)
        } else {
            javaClass.getResource("/parity/${dir.fileName}.json")?.readText()
        }
        if (text == null) {
            println("SKIPPED: $local not found (build the model in brain/ first)")
            return
        }
        val rows = Json.parseToJsonElement(text).jsonArray
        OnnxIntentModel(dir).use { model ->
            var tokens = 0; var intents = 0; var tags = 0; var maxDp = 0f
            var nearTies = 0
            val realFlips = mutableListOf<String>()
            var moods = 0; var moodRows = 0
            var embeddingRows = 0; var maxDe = 0f
            for (row in rows) {
                val r = row.jsonObject
                val text = r.getValue("text").jsonPrimitive.content
                val wantIds = r.getValue("input_ids").jsonArray.map { it.jsonPrimitive.long }
                val got = model.understand(text)!!
                if (model.tokenIds(text).toList() == wantIds) tokens++
                if (got.top.intent == r.getValue("intent").jsonPrimitive.content) intents++
                val wantTags = r.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
                if (got.tags == wantTags) {
                    tags++
                } else {
                    // int8 kernels differ per CPU (AVX2 vs AVX-512/VNNI), so a word whose two best tags are almost
                    // tied can flip. Only that is allowed; a confident disagreement is a real decoding bug.
                    val probs = model.slotProbabilities(text)
                    val flipped = got.tags.indices.filter { got.tags[it] != wantTags.getOrNull(it) }
                    val margins = flipped.map { w -> w to (probs[w][got.tags[w]] ?: 0f) - (probs[w][wantTags.getOrNull(w)] ?: 0f) }
                    val confident = margins.filter { (_, m) -> m >= NEAR_TIE }
                    nearTies += margins.size - confident.size
                    confident.forEach { (w, m) -> realFlips += "\"$text\" word $w: ${wantTags.getOrNull(w)} -> ${got.tags[w]} (margin $m)" }
                    if (got.tags.size == wantTags.size && confident.isEmpty()) tags++
                }
                maxDp = maxOf(maxDp, abs(got.top.confidence - r.getValue("p").jsonPrimitive.float))
                r["embedding8"]?.let { want ->
                    embeddingRows++
                    val e = assertNotNull(got.embedding, "the model has an embedding output, Kotlin must read it").values
                    want.jsonArray.forEachIndexed { i, v -> maxDe = maxOf(maxDe, abs(e[i] - v.jsonPrimitive.float)) }
                    val norm = kotlin.math.sqrt(e.sumOf { (it * it).toDouble() })
                    assertTrue(abs(norm - 1.0) < 1e-3, "embedding is unit length, was $norm")
                }
                r["mood"]?.let { want ->
                    moodRows++
                    if (got.mood?.mood == want.jsonPrimitive.content) moods++
                    maxDp = maxOf(maxDp, abs((got.mood?.confidence ?: 0f) - r.getValue("mood_p").jsonPrimitive.float))
                }
            }
            println(
                "parity over ${rows.size}: tokens $tokens, intents $intents, slot tags $tags (near-tie flips $nearTies), " +
                    "moods $moods/$moodRows, max |Δp| $maxDp, embeddings $embeddingRows (max |Δe| $maxDe)",
            )
            realFlips.forEach { println("slot flip: $it") }
            assertEquals(moodRows, moods, "moods differ")
            assertEquals(rows.size, tokens, "token ids differ")
            assertEquals(rows.size, intents, "intents differ")
            assertEquals(rows.size, tags, "slot tags differ: $realFlips")
            assertTrue(maxDp < MAX_CONFIDENCE_DRIFT, "confidences drift: $maxDp")
            assertTrue(maxDe < 1e-3f, "embedding values drift: $maxDe")
        }
    }

    private companion object {
        /** Largest probability gap between two slot tags that still counts as a tie across CPUs. */
        const val NEAR_TIE = 0.01f

        /**
         * Largest confidence difference from the Python reference. int8 kernels differ per CPU: bit-exact on the
         * reference laptop (Core Ultra, AVX-VNNI), 6e-7 on AMD EPYC 7763, 1.2e-3 on AMD EPYC 9V74 (AVX-512 VNNI),
         * all with identical tokens, intents, tags and moods. A Kotlin reading bug shows up far above this.
         */
        const val MAX_CONFIDENCE_DRIFT = 5e-3f
    }
}
