package dev.pebble.desktop

import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.Replies
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.brain.OnnxIntentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import dev.pebble.core.brain.PebbleActions as A

/**
 * The frozen gate (eval set v1) scored on what Pebble actually does: rules, then the int8 model,
 * then "Did you mean". The Python evaluator scores the model alone; this one is what you'd see.
 *  - right: acted straight away with the right action
 *  - asked: asked "Did you mean…" / "When…" with the right action as the first choice
 *  - wrong: anything else; "acted wrongly" (did the wrong thing without asking) is the worst kind
 */
class EvalSetRouterTest {
    private val brain: Path = ShippedModel.brain

    /** Set PEBBLE_EVAL_MODEL=models/intent-v1-pruned to score a candidate before it ships. */
    private val modelDir = brain.resolve(System.getenv("PEBBLE_EVAL_MODEL") ?: ShippedModel.dir.toString())

    @Test
    fun scoreV1() {
        if (!Files.exists(modelDir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        OnnxIntentModel(modelDir).use { model ->
            val router = CommandRouter({ model }, today = { 3 }) // a Wednesday, so weekday lines are stable
            val rows = Files.readAllLines(brain.resolve("eval/pebble_commands_v1.jsonl")).filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
            val score = linkedMapOf<String, IntArray>() // script → [right, asked, wrong]
            var actedWrongly = 0
            for (row in rows) {
                val text = row.getValue("text").jsonPrimitive.content
                val want = row.getValue("action").jsonPrimitive.content
                val script = row.getValue("script").jsonPrimitive.content
                val (verdict, got) = when (val r = router.route(text)) {
                    is Routed.Run -> actionOf(r.command).let { (if (it == want) 0 else 2) to "${r.source} $it" }

                    is Routed.Ask -> r.options.firstOrNull()?.action.let {
                        (if (it == want) 1 else 2) to
                            "ASK ${r.options.map { o -> o.action }}"
                    }

                    null -> 2 to "null"
                }
                score.getOrPut(script) { IntArray(3) }[verdict]++
                if (verdict == 2 && !got.startsWith("ASK")) actedWrongly++
                if (verdict != 0) println("  ${if (verdict == 1) "?" else "x"} ${text.take(50).padEnd(50)} want $want, got $got")
            }
            var right = 0; var asked = 0; var n = 0
            for ((script, s) in score) {
                println("$script: ${s[0]} right, ${s[1]} asked, ${s[2]} wrong (of ${s.sum()})")
                right += s[0]; asked += s[1]; n += s.sum()
            }
            println("TOTAL: $right/$n right, ${right + asked}/$n right or right-first-choice, $actedWrongly acted wrongly")
            // The gate: a new model must not drop below the baseline it replaces.
            assertTrue(right + asked >= BASELINE_RIGHT_OR_ASKED, "router v1 score fell below baseline")
            assertTrue(actedWrongly <= MAX_ACTED_WRONGLY, "Pebble acted wrongly $actedWrongly times (max $MAX_ACTED_WRONGLY)")
        }
    }

    companion object {
        // Gates for intent-v2r-pruned (2026-10-04). Raise them when a better model ships.

        /** Doing the wrong thing without asking; may never go up. */
        const val MAX_ACTED_WRONGLY = 0
        const val BASELINE_RIGHT_OR_ASKED = 68 // intent-v2r-pruned: 65 right + 3 asked, of 68 (v2-pruned: 67)
    }
}
