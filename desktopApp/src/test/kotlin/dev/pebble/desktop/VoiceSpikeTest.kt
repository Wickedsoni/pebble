package dev.pebble.desktop

import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import dev.pebble.desktop.brain.OnnxIntentModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spike (plan 3.0): can sherpa-onnx (its own onnxruntime.dll) run in the same JVM as the intent model
 * (Microsoft's onnxruntime jar), and how fast are VAD, GTCRN and Whisper on this CPU?
 * PEBBLE_SPIKE_ORDER=sherpa-first loads sherpa before the intent model. Skipped without the models.
 */
class VoiceSpikeTest {
    private val asr: Path = ShippedModel.brain.resolve("models/asr")

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test
    fun sherpaBesideTheIntentModel() {
        if (!Files.exists(asr.resolve("sherpa-onnx-whisper-base/base-encoder.int8.onnx"))) { println("SKIPPED: no asr models"); return }
        val sherpaFirst = System.getenv("PEBBLE_SPIKE_ORDER") == "sherpa-first"
        var intent: OnnxIntentModel? = null
        if (!sherpaFirst) intent = OnnxIntentModel(ShippedModel.dir)

        var t0 = System.nanoTime()
        LibraryUtils.load()
        println("SPIKE sherpa native load: ${ms(t0)} ms (order: ${if (sherpaFirst) "sherpa first" else "intent model first"})")
        if (sherpaFirst) intent = OnnxIntentModel(ShippedModel.dir)

        // VAD on a clip with speech.
        val wave = WaveReader(asr.resolve("inp_16k.wav").toString())
        val vad = Vad(
            VadModelConfig.builder()
                .setSileroVadModelConfig(
                    SileroVadModelConfig.builder().setModel(asr.resolve("silero_vad.onnx").toString())
                        .setThreshold(0.5f).setMinSilenceDuration(0.3f).setMinSpeechDuration(0.25f).setWindowSize(512).build(),
                )
                .setSampleRate(16_000).setNumThreads(1).build(),
        )
        t0 = System.nanoTime()
        val samples = wave.samples
        var i = 0
        while (i + 512 <= samples.size) { vad.acceptWaveform(samples.copyOfRange(i, i + 512)); i += 512 }
        vad.flush()
        var speech = 0
        while (!vad.empty()) { speech += vad.front().samples.size; vad.pop() }
        println("SPIKE VAD: ${samples.size / 16} ms audio -> ${speech / 16} ms speech in ${ms(t0)} ms")
        vad.release()

        // GTCRN denoise.
        val noisy = WaveReader(asr.resolve("speech_with_noise.wav").toString())
        val denoiser = OfflineSpeechDenoiser(
            OfflineSpeechDenoiserConfig.builder().setModel(
                OfflineSpeechDenoiserModelConfig.builder()
                    .setGtcrn(OfflineSpeechDenoiserGtcrnModelConfig.builder().setModel(asr.resolve("gtcrn_simple.onnx").toString()).build())
                    .setNumThreads(1).build(),
            ).build(),
        )
        t0 = System.nanoTime()
        val clean = denoiser.run(noisy.samples, noisy.sampleRate)
        println("SPIKE GTCRN: ${noisy.samples.size * 1000L / noisy.sampleRate} ms audio denoised in ${ms(t0)} ms")
        denoiser.release()

        for (size in listOf("base", "small")) {
            val dir = asr.resolve("sherpa-onnx-whisper-$size")
            if (!Files.exists(dir.resolve("$size-encoder.int8.onnx"))) { println("SPIKE whisper-$size: not downloaded"); continue }
            val rt = Runtime.getRuntime()
            val before = rt.totalMemory() - rt.freeMemory()
            t0 = System.nanoTime()
            val recognizer = OfflineRecognizer(
                OfflineRecognizerConfig.builder().setOfflineModelConfig(
                    OfflineModelConfig.builder()
                        .setWhisper(
                            OfflineWhisperModelConfig.builder()
                                .setEncoder(dir.resolve("$size-encoder.int8.onnx").toString())
                                .setDecoder(dir.resolve("$size-decoder.int8.onnx").toString())
                                .setLanguage("").setTask("transcribe").build(),
                        )
                        .setTokens(dir.resolve("$size-tokens.txt").toString())
                        .setNumThreads(4).build(),
                ).setDecodingMethod("greedy_search").build(),
            )
            val load = ms(t0)
            for ((name, audio, rate) in listOf(
                Triple("clean", samples, wave.sampleRate),
                Triple("noisy", noisy.samples, noisy.sampleRate),
                Triple("denoised", clean.samples, clean.sampleRate),
            )) {
                val stream = recognizer.createStream()
                stream.acceptWaveform(audio, rate)
                t0 = System.nanoTime()
                recognizer.decode(stream)
                val r = recognizer.getResult(stream)
                println("SPIKE whisper-$size $name (${audio.size * 1000L / rate} ms audio): ${ms(t0)} ms [${r.lang}] \"${r.text.trim()}\"")
                stream.release()
            }
            println(
                "SPIKE whisper-$size load $load ms, " +
                    "JVM heap delta ${(rt.totalMemory() - rt.freeMemory() - before) / 1_048_576} MB (native not counted)",
            )
            recognizer.release()
        }

        // The intent model must still work with both runtimes in the process.
        val u = intent!!.understand("kal subah 7 baje utha dena")
        println("SPIKE intent model after sherpa: ${u?.top}")
        assertTrue(u?.top?.intent == "alarm_set")
        intent.close()
    }
}
