package dev.pebble.desktop

import dev.pebble.desktop.brain.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The installed app has no `brain/` folder next to it: the model must come from the installer's
 * resources (`<install>/app/resources/models/intent`). Run `:desktopApp:createDistributable` first.
 */
class BundledModelTest {
    private val root: Path = Path.of(System.getProperty("user.dir"))
    private val resources = root.resolve("build/compose/binaries/main/app/Pebble/app/resources")

    @Test
    fun installedLayoutLoadsTheBundledModel() {
        if (!Files.exists(resources.resolve("models/intent/intent.int8.onnx"))) { println("SKIPPED: no distributable"); return }
        if (Files.exists(dev.pebble.core.db.DatabaseFactory.defaultDataDir().toPath().resolve("models/intent"))) {
            println("SKIPPED: a model in %APPDATA% would win"); return
        }
        // Resolve repo paths before user.dir is pointed away (ShippedModel reads it).
        val fleurs = ShippedModel.brain.resolve("data/raw/fleurs_hi/dev")
        ShippedModel.brain // resolve now: user.dir changes below
        val oldDir = System.getProperty("user.dir")
        try {
            System.setProperty("compose.application.resources.dir", resources.toString())
            System.setProperty("user.dir", Files.createTempDirectory("pebble-installed").toString()) // no brain/ here
            val mm = ModelManager(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            assertEquals(resources.resolve("models/intent"), mm.modelDir)
            mm.warmUp()
            assertTrue(runBlocking { mm.awaitLoaded() }, "bundled model failed to load: ${mm.status}")
            val u = assertNotNull(mm.understand("kal subah 7 baje utha dena"))
            assertEquals("alarm_set", u.top.intent)

            // The voice model ships too: found in the bundle, checksum-verified, and hears Hindi.
            if (Files.exists(resources.resolve("models/asr/silero_vad.onnx"))) {
                val speech = dev.pebble.desktop.voice.SpeechRecognizer(CoroutineScope(SupervisorJob() + Dispatchers.Default))
                assertEquals(resources.resolve("models/asr"), speech.modelDir)
                val clip = fleurs
                val wav = if (Files.exists(clip)) {
                    Files.list(clip).use {
                        it.filter { p -> p.toString().endsWith(".wav") }.findFirst().orElse(null)
                    }
                } else {
                    null
                }
                if (wav != null) {
                    val t = runBlocking { speech.transcribe(com.k2fsa.sherpa.onnx.WaveReader(wav.toString()).samples) }
                    assertNotNull(t, "bundled speech model heard nothing: ${speech.status}")
                    assertTrue(t.text.any { it in 'ऀ'..'ॿ' } || t.language == "en", "expected Hindi text, got ${t.text}")
                    println("BUNDLED ASR: [${t.language}] ${t.text.take(60)} (${t.decodeMillis} ms)")
                    // Command-length clips: what a push-to-talk command actually costs.
                    val full = com.k2fsa.sherpa.onnx.WaveReader(wav.toString()).samples
                    for (sec in listOf(3, 5)) {
                        val cut = full.copyOfRange(0, minOf(full.size, sec * 16_000))
                        val t0 = System.currentTimeMillis()
                        val c = runBlocking { speech.transcribe(cut) }
                        println("BUNDLED ASR ${sec}s: ${System.currentTimeMillis() - t0} ms total -> ${c?.text}")
                    }
                    // English goes to the Whisper fallback (Dolphin doesn't transcribe English).
                    val en = ShippedModel.brain.resolve("models/asr/sherpa-onnx-whisper-base/test_wavs/0.wav")
                    if (Files.exists(en)) {
                        val t1 = System.currentTimeMillis()
                        val e = runBlocking { speech.transcribe(com.k2fsa.sherpa.onnx.WaveReader(en.toString()).samples) }
                        println("BUNDLED ASR english: ${System.currentTimeMillis() - t1} ms -> [${e?.language}] ${e?.text}")
                        assertEquals("en", e?.language)
                    }
                }
            }
        } finally {
            System.setProperty("user.dir", oldDir)
            System.clearProperty("compose.application.resources.dir")
        }
    }
}
