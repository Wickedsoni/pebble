package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.search.MemorySearch
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.desktop.brain.OnnxIntentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Memory search on the frozen set brain/eval/memory_search_v1.jsonl, with the shipped command model:
 * is the right note the first result? Gate: [MIN_TOP1] (the score this model and ranking reached in WP C2).
 */
class MemorySearchEvalTest {
    @Test
    fun theRightNoteComesFirst() {
        val eval = ShippedModel.brain.resolve("eval/memory_search_v1.jsonl")
        if (!Files.exists(ShippedModel.dir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        val rows = Files.readAllLines(eval).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        OnnxIntentModel(ShippedModel.dir).use { model ->
            val db = DatabaseFactory.inMemory()
            val notes = NoteRepository(db)
            rows.filter {
                it.getValue("type").jsonPrimitive.content == "item"
            }.forEach { notes.add(it.getValue("text").jsonPrimitive.content, 1) }
            val search = MemorySearch(db, embed = { model.understand(it)?.embedding }, modelVersion = { "eval" }, clock = { 1L })
            search.reconcile()
            var top1 = 0
            val queries = rows.filter { it.getValue("type").jsonPrimitive.content == "query" }
            for (q in queries) {
                val text = q.getValue("text").jsonPrimitive.content
                val expect = q.getValue("expect").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val hits = search.search(text, limit = 3)
                val ok = hits.firstOrNull()?.text in expect
                if (ok) top1++
                println(
                    "  ${if (ok) "ok" else "x "} ${text.padEnd(18)} -> ${hits.joinToString(" | ") {
                        "%.2f %s".format(it.score, it.text.take(30))
                    }}",
                )
            }
            println("MEMORY SEARCH: $top1/${queries.size} right first")
            assertTrue(top1 >= MIN_TOP1, "memory search fell below the gate: $top1 < $MIN_TOP1")
        }
    }

    private companion object {
        /** intent-v3-pruned + 0.6 meaning / 0.4 shared words (WP C2). Raise it when search gets better. */
        const val MIN_TOP1 = 14
    }
}
