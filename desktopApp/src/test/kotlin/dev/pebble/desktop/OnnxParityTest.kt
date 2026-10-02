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
        val ref = dir.resolve("parity.json")
        if (!Files.exists(ref)) {
            println("SKIPPED: ${ref} not found (build the model in brain/ first)")
            return
        }
        val rows = Json.parseToJsonElement(Files.readString(ref)).jsonArray
        OnnxIntentModel(dir).use { model ->
            var tokens = 0; var intents = 0; var tags = 0; var maxDp = 0f
            var moods = 0; var moodRows = 0
            for (row in rows) {
                val r = row.jsonObject
                val text = r.getValue("text").jsonPrimitive.content
                val wantIds = r.getValue("input_ids").jsonArray.map { it.jsonPrimitive.long }
                val got = model.understand(text)!!
                if (model.tokenIds(text).toList() == wantIds) tokens++
                if (got.top.intent == r.getValue("intent").jsonPrimitive.content) intents++
                if (got.tags == r.getValue("tags").jsonArray.map { it.jsonPrimitive.content }) tags++
                maxDp = maxOf(maxDp, abs(got.top.confidence - r.getValue("p").jsonPrimitive.float))
                r["mood"]?.let { want ->
                    moodRows++
                    if (got.mood?.mood == want.jsonPrimitive.content) moods++
                    maxDp = maxOf(maxDp, abs((got.mood?.confidence ?: 0f) - r.getValue("mood_p").jsonPrimitive.float))
                }
            }
            println("parity over ${rows.size}: tokens $tokens, intents $intents, slot tags $tags, moods $moods/$moodRows, max |Δp| $maxDp")
            assertEquals(moodRows, moods, "moods differ")
            assertEquals(rows.size, tokens, "token ids differ")
            assertEquals(rows.size, intents, "intents differ")
            assertEquals(rows.size, tags, "slot tags differ")
            assertTrue(maxDp < 1e-3f, "confidences drift: $maxDp")
        }
    }
}
