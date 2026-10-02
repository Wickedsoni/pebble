package dev.pebble.desktop.voice

import ai.onnxruntime.OrtEnvironment
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.pebble.core.db.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/** What Whisper heard. [language] is "hi" or "en"; [audioMillis] is speech after trimming silence. */
data class Transcript(val text: String, val language: String, val audioMillis: Long, val decodeMillis: Long)

/**
 * Speech to text, fully offline (sherpa-onnx): Silero VAD trims silence, Whisper transcribes.
 * Same lifecycle as the command model's ModelManager: loads on demand ([warmUp] when the talk key
 * goes down, so loading overlaps your speech), frees itself after [idleMillis], and degrades to
 * "no voice" (null) instead of failing if anything is missing.
 *
 * Language: Whisper detects it; Hindi speech is often detected as Urdu (and written in Urdu script),
 * so anything that isn't English is decoded again as Hindi — Pebble understands Devanagari and Roman.
 */
class SpeechRecognizer(private val scope: CoroutineScope, private val idleMillis: Long = 10 * 60_000L) {
    @Volatile private var whisperAuto: OfflineRecognizer? = null

    @Volatile private var whisperHindi: OfflineRecognizer? = null

    @Volatile private var vad: Vad? = null

    @Volatile private var lastUse = 0L

    @Volatile var status: String = "not loaded"
        private set
    private var loading: Job? = null

    val modelDir: Path? by lazy { locate() }

    @Synchronized
    fun warmUp() {
        lastUse = System.currentTimeMillis()
        if (whisperAuto != null || loading?.isActive == true) return
        val dir = modelDir ?: run { status = "no speech model found"; return }
        loading = scope.launch(Dispatchers.IO) {
            status = "loading"
            runCatching {
                // The command model's onnxruntime must be in the process first: sherpa binds to it, not the
                // other way round (VoiceSpikeTest — the reverse order shifts the command model's numbers).
                OrtEnvironment.getEnvironment()
                LibraryUtils.load()
                val size = whisperSize(dir)
                hexTokens = Files.exists(dir.resolve("HEX_TOKENS"))
                whisperAuto = recognizer(dir, size, language = "")
                whisperHindi = recognizer(dir, size, language = "hi")
                vad = Vad(
                    VadModelConfig.builder().setSileroVadModelConfig(
                        SileroVadModelConfig.builder().setModel(dir.resolve("silero_vad.onnx").toString())
                            .setThreshold(0.5f).setMinSilenceDuration(0.3f).setMinSpeechDuration(0.2f)
                            .setMaxSpeechDuration(15f).setWindowSize(512).build(),
                    ).setSampleRate(AudioPrep.SAMPLE_RATE).setNumThreads(1).build(),
                )
                status = "ready (whisper-$size)"
                watchIdle()
            }.onFailure { status = "load failed: ${it.message}" }
        }
    }

    /** Waits for loading, then transcribes [samples] (16 kHz mono). Null: no model, or nothing was said. */
    suspend fun transcribe(samples: FloatArray): Transcript? {
        warmUp()
        loading?.join()
        val auto = whisperAuto ?: return null
        lastUse = System.currentTimeMillis()
        val speech = trim(AudioPrep.prepare(samples))
        if (speech.size < AudioPrep.SAMPLE_RATE / 4) return null // < 250 ms of speech: nothing to hear
        val t0 = System.currentTimeMillis()
        var (text, lang) = decode(auto, speech)
        if (lang != "en") {
            val hi = whisperHindi
            if (hi != null) { text = decode(hi, speech).first; lang = "hi" }
        }
        val cleaned = text.trim().takeIf { it.isNotEmpty() && !isHallucination(it) } ?: return null
        return Transcript(cleaned, lang, speech.size * 1000L / AudioPrep.SAMPLE_RATE, System.currentTimeMillis() - t0)
    }

    @Volatile private var hexTokens = false

    private fun decode(r: OfflineRecognizer, x: FloatArray): Pair<String, String> {
        val s = r.createStream()
        try {
            s.acceptWaveform(x, AudioPrep.SAMPLE_RATE)
            r.decode(s)
            val res = r.getResult(s)
            val text = if (hexTokens) WhisperText.fromHex(res.text) else res.text
            return text to res.lang.trim('<', '|', '>', ' ')
        } finally {
            s.release()
        }
    }

    /** Keeps only the speech Silero found (plus its own padding), so Whisper never sees long silence. */
    @Synchronized
    private fun trim(x: FloatArray): FloatArray {
        val v = vad ?: return x
        v.reset()
        var i = 0
        while (i + 512 <= x.size) { v.acceptWaveform(x.copyOfRange(i, i + 512)); i += 512 }
        v.flush()
        val parts = mutableListOf<FloatArray>()
        while (!v.empty()) { parts += v.front().samples; v.pop() }
        if (parts.isEmpty()) return FloatArray(0)
        val pad = FloatArray(AudioPrep.SAMPLE_RATE / 10) // 100 ms between pieces
        return parts.flatMapIndexed { k, p -> if (k == 0) p.toList() else pad.toList() + p.toList() }.toFloatArray()
    }

    private fun recognizer(dir: Path, size: String, language: String) = OfflineRecognizer(
        OfflineRecognizerConfig.builder().setOfflineModelConfig(
            OfflineModelConfig.builder().setWhisper(
                OfflineWhisperModelConfig.builder()
                    .setEncoder(dir.resolve("$size-encoder.int8.onnx").toString())
                    .setDecoder(dir.resolve("$size-decoder.int8.onnx").toString())
                    .setLanguage(language).setTask("transcribe").build(),
            ).setTokens(dir.resolve("$size-tokens.txt").toString()).setNumThreads(THREADS).build(),
        ).setDecodingMethod("greedy_search").build(),
    )

    private fun watchIdle() = scope.launch {
        while (isActive && whisperAuto != null) {
            delay(60_000)
            if (System.currentTimeMillis() - lastUse > idleMillis) unload()
        }
    }

    @Synchronized
    private fun unload() {
        whisperAuto?.release(); whisperHindi?.release(); vad?.release()
        whisperAuto = null; whisperHindi = null; vad = null
        status = "unloaded (idle)"
    }

    private fun locate(): Path? {
        val cwd = Path.of(System.getProperty("user.dir"))
        val candidates = listOfNotNull(
            System.getenv("PEBBLE_ASR_DIR")?.let { Path.of(it) },
            DatabaseFactory.defaultDataDir().toPath().resolve("models/asr"),
            System.getProperty("compose.application.resources.dir")?.let { Path.of(it).resolve("models/asr") },
            cwd.resolve("../brain/models/asr-whisper-small").normalize(),
            cwd.resolve("brain/models/asr-whisper-small").normalize(),
        )
        return candidates.firstOrNull { d -> Files.exists(d.resolve("silero_vad.onnx")) && runCatching { whisperSize(d) }.isSuccess }
    }

    companion object {
        private const val THREADS = 4

        /** The Whisper size in [dir], from its token file name (base-tokens.txt → "base"). */
        fun whisperSize(dir: Path): String = Files.list(dir).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith("-tokens.txt") }.findFirst().get().removeSuffix("-tokens.txt")
        }

        /** Whisper's classic inventions on near-silence (it was trained on subtitled video). */
        private val hallucinations = listOf("thank you for watching", "thanks for watching", "please subscribe", "[music]", "(music)")

        fun isHallucination(text: String): Boolean {
            val t = text.lowercase().trim()
            return hallucinations.any { t == it || (t.length < 40 && it in t) }
        }
    }
}
