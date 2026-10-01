package dev.pebble.desktop

import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.brain.OnnxIntentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** End to end with the real model: what Pebble would actually do with each eval sentence. */
class RouterWithModelTest {
    private val brain: Path = Path.of(System.getProperty("user.dir")).parent.resolve("brain")
    private val modelDir = brain.resolve("models/intent-v0-pruned")

    @Test
    fun routesEvalSentences() {
        if (!Files.exists(modelDir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        OnnxIntentModel(modelDir).use { model ->
            val router = CommandRouter({ model })
            val lines = Files.readAllLines(brain.resolve("eval/pebble_commands_v0.jsonl")).filter { it.isNotBlank() }
            for (line in lines) {
                val text = Json.parseToJsonElement(line).jsonObject.getValue("text").jsonPrimitive.content
                val r = router.route(text)
                val shown = when (r) {
                    is Routed.Run -> "${r.source.name.padEnd(8)} ${r.command}"
                    is Routed.Ask -> "ASK      ${r.question} ${r.options.map { it.label }}"
                    null -> "null"
                }
                println("${text.take(48).padEnd(48)} → $shown")
            }

            // Spot checks on the cases that matter most.
            val r1 = router.route("shaam 7 baje mummy ko call karne ki yaad dila dena") as Routed.Run
            val c1 = r1.command as QuickCommand.RemindAt
            assertEquals(19, c1.hour)
            val r2 = router.route("कल सुबह छह बजे मुझे जगा देना") as Routed.Run
            val c2 = r2.command as QuickCommand.RemindAt
            assertEquals(6 to 1, c2.hour to c2.dayOffset)
            assertIs<QuickCommand.TellTime>((router.route("abhi kitne baje hain") as Routed.Run).command)
            assertIs<QuickCommand.SetInterval>((router.route("water every 45m") as Routed.Run).command) // rules first
        }
    }
}
