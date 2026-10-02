package dev.pebble.desktop

import com.k2fsa.sherpa.onnx.WaveReader
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.desktop.brain.OnnxIntentModel
import dev.pebble.desktop.voice.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole voice path on your own recordings (brain/eval/voice_v1, made with recordVoiceEval):
 * clip → AudioPrep → Silero VAD → Whisper → CommandRouter → did Pebble do the right thing?
 * Also the latency from "key released" to transcript. Skipped until you've recorded clips.
 */
class VoiceCommandEvalTest {
    private val dir = ShippedModel.brain.resolve("eval/voice_v1")

    @Test
    fun yourVoiceToTheRightAction() {
        val refs = dir.resolve("refs.jsonl")
        if (!Files.exists(refs)) { println("SKIPPED: record clips with ./gradlew :desktopApp:recordVoiceEval"); return }
        OnnxIntentModel(ShippedModel.dir).use { model ->
            val router = CommandRouter({ model }, today = { 3 })
            val speech = SpeechRecognizer(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            if (speech.modelDir == null) { println("SKIPPED: no speech model (set PEBBLE_ASR_DIR)"); return }
            var right = 0; var asked = 0; var heardNothing = 0; var n = 0
            val latencies = mutableListOf<Long>()
            for (line in Files.readAllLines(refs).filter { it.isNotBlank() }) {
                val r = Json.parseToJsonElement(line).jsonObject
                val want = r.getValue("action").jsonPrimitive.content
                val wav = WaveReader(dir.resolve(r.getValue("file").jsonPrimitive.content).toString())
                val t = runBlocking { speech.transcribe(wav.samples) }
                n++
                if (t == null) { heardNothing++; println("  ∅ heard nothing   want $want"); continue }
                latencies += t.decodeMillis
                val verdict = when (val routed = router.route(t.text)) {
                    is Routed.Run -> if (actionOf(routed.command) == want) "✓".also { right++ } else "x ${actionOf(routed.command)}"
                    is Routed.Ask -> if (routed.options.firstOrNull()?.action == want) "?".also { asked++ } else "x ask"
                    null -> "x"
                }
                println("  $verdict  [${t.language}] \"${t.text}\"  (said: \"${r.getValue("text").jsonPrimitive.content}\", want $want)")
            }
            val median = latencies.sorted().getOrNull(latencies.size / 2)
            println(
                "VOICE: $right/$n right, ${right + asked}/$n incl. right first choice, " +
                    "$heardNothing heard nothing, median decode $median ms",
            )
            assertTrue(n == 0 || heardNothing < n, "recognizer heard nothing at all — mic level or model problem")
        }
    }
}
