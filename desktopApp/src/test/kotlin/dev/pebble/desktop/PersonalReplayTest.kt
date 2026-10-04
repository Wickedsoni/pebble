package dev.pebble.desktop

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.PersonalLayer
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.desktop.brain.OnnxIntentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The prequential replay gate of the personal layer (WP C3, ADR 0010), on the shipped int8 model.
 *
 * brain/eval/personal_replay_v1.jsonl is one user's sentences in time order ("say", with the action meant)
 * and phrases taught on the Memory page ("teach"). At each sentence, the layer knows only the rows before it.
 * The user's answers follow what Pebble did with the layer: a wrong action gets "Not what I meant" and a pick,
 * a question gets a pick. Gates:
 *  - with the layer, not fewer right and not more "acted wrongly" than the model alone
 *  - after the whole replay, the frozen eval set v1 keeps its baseline and 0 "acted wrongly" (no spread)
 */
class PersonalReplayTest {
    private val brain = ShippedModel.brain

    private class Score {
        var right = 0
        var asked = 0
        var wrong = 0
        var actedWrongly = 0

        fun add(r: Routed?, want: String) {
            when {
                r is Routed.Run && actionOf(r.command) == want -> right++

                r is Routed.Ask && r.options.firstOrNull()?.action == want -> asked++

                else -> {
                    wrong++
                    if (r is Routed.Run) actedWrongly++
                }
            }
        }

        override fun toString() = "$right right, $asked asked, $wrong wrong ($actedWrongly acted wrongly)"
    }

    @Test
    fun replay() {
        if (!Files.exists(ShippedModel.dir.resolve("intent.int8.onnx"))) { println("SKIPPED: no model"); return }
        OnnxIntentModel(ShippedModel.dir).use { model ->
            val feedback = CommandFeedbackRepository(DatabaseFactory.inMemory())
            var now = 0L
            val layer = PersonalLayer(
                source = { feedback.personalSince(0) },
                embed = { model.understand(it)?.embedding },
                modelVersion = { "replay" },
                hourOf = { 12 },
                clock = { now },
            )
            val alone = CommandRouter({ model }, today = { 3 })
            val personal = CommandRouter({ model }, today = { 3 }, personal = layer)
            val without = Score()
            val with = Score()
            val steps = Files.readAllLines(brain.resolve("eval/personal_replay_v1.jsonl")).filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
            for (step in steps) {
                now += 60_000
                val text = step.getValue("text").jsonPrimitive.content
                val want = step.getValue("action").jsonPrimitive.content
                if (step.getValue("step").jsonPrimitive.content == "teach") {
                    feedback.teach(text, want, now)
                    layer.refresh()
                    continue
                }
                val before = alone.route(text)
                val after = personal.route(text)
                without.add(before, want)
                with.add(after, want)
                if (after !is Routed.Run || actionOf(after.command) != want) {
                    println("  ${text.padEnd(24)} want $want, alone ${describe(before)}, with layer ${describe(after)}")
                }
                // What the user does next, as in PebbleApp.executeFromModel / converseChoice.
                when (after) {
                    is Routed.Run -> if (after.action != null) {
                        val id = feedback.record(text, after.action!!, after.understood, now, CommandFeedbackRepository.CONFIRMED)
                        if (actionOf(after.command) != want) {
                            feedback.markWrong(id)
                            feedback.record(text, want, after.understood, now + 1)
                        }
                    }

                    is Routed.Ask -> feedback.record(text, want, after.understood, now)

                    null -> Unit
                }
                layer.refresh()
            }
            println("REPLAY model alone: $without")
            println("REPLAY with layer:  $with")
            assertTrue(with.right >= without.right, "the layer made fewer commands right")
            assertTrue(with.actedWrongly <= without.actedWrongly, "the layer acted wrongly more often")

            // No spread: what this user taught must not change how Pebble reads the frozen eval set.
            val eval = Score()
            Files.readAllLines(brain.resolve("eval/pebble_commands_v1.jsonl")).filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .forEach { row ->
                    val text = row.getValue("text").jsonPrimitive.content
                    val r = personal.route(text)
                    eval.add(r, row.getValue("action").jsonPrimitive.content)
                    val before = alone.route(text)
                    if (describe(r) != describe(before)) println("  eval changed: $text: ${describe(before)} -> ${describe(r)}")
                }
            println("REPLAY eval v1 with this user's layer: $eval")
            assertTrue(eval.right + eval.asked >= EvalSetRouterTest.BASELINE_RIGHT_OR_ASKED, "the layer lowered the eval v1 score")
            assertTrue(eval.actedWrongly <= EvalSetRouterTest.MAX_ACTED_WRONGLY, "the layer made Pebble act wrongly on eval v1")
        }
    }

    private fun describe(r: Routed?) = when (r) {
        is Routed.Run -> "RUN ${actionOf(r.command)}"
        is Routed.Ask -> "ASK ${r.options.map { it.action }}"
        null -> "nothing"
    }
}
