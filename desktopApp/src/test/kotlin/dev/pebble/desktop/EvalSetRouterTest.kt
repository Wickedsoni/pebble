package dev.pebble.desktop

import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.PebbleActions as A
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

/**
 * The frozen gate (eval set v1) scored on what Pebble actually does: rules, then the int8 model,
 * then "Did you mean". The Python evaluator scores the model alone; this one is what you'd see.
 *  - right: acted straight away with the right action
 *  - asked: asked "Did you mean…" / "When…" with the right action as the first choice
 *  - wrong: anything else
 */
class EvalSetRouterTest {
    private val brain: Path = Path.of(System.getProperty("user.dir")).parent.resolve("brain")
    /** Set PEBBLE_EVAL_MODEL=models/intent-v1-pruned to score a candidate before it ships. */
    private val modelDir = brain.resolve(System.getenv("PEBBLE_EVAL_MODEL") ?: "models/intent-v0-pruned")

    private fun actionOf(c: QuickCommand): String = when (c) {
        is QuickCommand.RemindAt, is QuickCommand.RemindIn -> A.REMIND
        is QuickCommand.AddNote -> A.ADD_NOTE
        is QuickCommand.RememberFact -> "remember_fact"
        is QuickCommand.LogWater -> "log_water"
        is QuickCommand.SetInterval -> "set_interval"
        QuickCommand.ShowUpcoming -> A.REMINDERS_QUERY
        QuickCommand.ShowNotes -> A.NOTES_QUERY
        QuickCommand.TellTime -> A.TIME_QUERY
        is QuickCommand.Chitchat -> if (c.intent == Replies.LOW_MOOD) "mood" else A.CHITCHAT
        is QuickCommand.Unsupported -> A.OTHER
        is QuickCommand.OpenPage -> if (c.page == "notes") A.NOTE_REMOVE else A.REMINDER_REMOVE
    }

    @Test
    fun scoreV1() {
        if (!Files.exists(modelDir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        OnnxIntentModel(modelDir).use { model ->
            val router = CommandRouter({ model }, today = { 3 }) // a Wednesday, so weekday lines are stable
            val rows = Files.readAllLines(brain.resolve("eval/pebble_commands_v1.jsonl")).filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
            val score = linkedMapOf<String, IntArray>() // script → [right, asked, wrong]
            for (row in rows) {
                val text = row.getValue("text").jsonPrimitive.content
                val want = row.getValue("action").jsonPrimitive.content
                val script = row.getValue("script").jsonPrimitive.content
                val (verdict, got) = when (val r = router.route(text)) {
                    is Routed.Run -> actionOf(r.command).let { (if (it == want) 0 else 2) to "${r.source} $it" }
                    is Routed.Ask -> r.options.firstOrNull()?.action.let { (if (it == want) 1 else 2) to "ASK ${r.options.map { o -> o.action }}" }
                    null -> 2 to "null"
                }
                score.getOrPut(script) { IntArray(3) }[verdict]++
                if (verdict != 0) println("  ${if (verdict == 1) "?" else "x"} ${text.take(50).padEnd(50)} want $want, got $got")
            }
            var right = 0; var asked = 0; var n = 0
            for ((script, s) in score) {
                println("$script: ${s[0]} right, ${s[1]} asked, ${s[2]} wrong (of ${s.sum()})")
                right += s[0]; asked += s[1]; n += s.sum()
            }
            println("TOTAL: $right/$n right, ${right + asked}/$n right or right-first-choice")
            // The gate: a new model must not drop below the baseline it replaces.
            assertTrue(right + asked >= BASELINE_RIGHT_OR_ASKED, "router v1 score fell below baseline")
        }
    }

    companion object {
        /** intent-v0-pruned, 2026-10-02. Raise this when a better model ships. */
        const val BASELINE_RIGHT_OR_ASKED = 61 // 58 right + 3 asked, of 68 (v0 model + Hindi mood / "aadhe ghante" rules)
    }
}
