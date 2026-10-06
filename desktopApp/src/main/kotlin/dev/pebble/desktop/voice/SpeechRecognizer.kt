package dev.pebble.desktop.voice

import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.desktop.brain.LazyModel
import dev.pebble.desktop.brain.ModelChecksums
import dev.pebble.desktop.brain.ModelRuntime
import dev.pebble.desktop.brain.VerifiedModelCache
import dev.pebble.desktop.brain.buildOrClose
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path

/** What Whisper heard. [language] is "hi" or "en"; [audioMillis] is speech after trimming silence. */
data class Transcript(val text: String, val language: String, val audioMillis: Long, val decodeMillis: Long)

/**
 * Speech to text, fully offline (sherpa-onnx): Silero VAD trims silence, then a two-step cascade:
 *  1. Dolphin-base CTC — Hindi / Hinglish straight to Devanagari, ~16% CER on FLEURS Hindi, ~60 ms for 5 s.
 *  2. Whisper-base in English — only when Dolphin heard (almost) nothing: Dolphin doesn't do English.
 * Same lifecycle as the command model (both use [LazyModel]): loads on demand ([warmUp] when the talk key
 * goes down, so loading overlaps your speech), frees itself after [idleMillis], and degrades to
 * "no voice" (null) instead of failing if anything is missing.
 *
 * A Whisper-only package (no ctc-model) still works: Whisper auto-detects, with the Devanagari fixes in
 * [WhisperText]; that path is kept for evaluation, Pebble ships the cascade (prepare_asr.py voice).
 */
class SpeechRecognizer(
    scope: CoroutineScope,
    idleMillis: Long = 10 * 60_000L,
    /** Skips re-hashing ~300 MB of speech models that passed before (null: hash every load). */
    private val cache: VerifiedModelCache? = null,
) {
    /** The loaded speech models, freed together. */
    private class AsrEngines(
        /** Dolphin CTC (Hindi and other Eastern languages); null in a Whisper-only package. */
        val ctc: OfflineRecognizer?,
        /** Whisper: English fallback in the cascade, or auto-detect in a Whisper-only package. */
        val whisper: OfflineRecognizer,
        val vad: Vad,
        val hexTokens: Boolean,
        val size: String,
    ) : AutoCloseable {
        override fun close() {
            ctc?.release()
            whisper.release()
            vad.release()
        }
    }

    private val loader = LazyModel(
        name = "asr",
        scope = scope,
        idleMillis = idleMillis,
        locate = ::locate,
        verify = { ModelChecksums.verify(it, "asr", cache) },
        load = ::loadEngines,
        describe = { if (it.ctc != null) "dolphin + whisper-${it.size} for English, " else "whisper-${it.size}, " },
    )

    /** Status for logs and diagnostics ("ready (dolphin + whisper-base for English, 900 ms load)", …). */
    val status: String get() = loader.status.value.toString()

    val modelDir: Path? get() = loader.dir

    /** Start loading (the talk key just went down, so loading overlaps your speech). */
    fun warmUp() = loader.warmUp()

    /** Waits for loading, then transcribes [samples] (16 kHz mono). Null: no model, or nothing was said. */
    suspend fun transcribe(samples: FloatArray): Transcript? = loader.awaitUse { e ->
        // The engines stay open until this block ends: an idle unload cannot free them under the decoder.
        val speech = trim(e.vad, AudioPrep.prepare(samples))
        if (speech.size < AudioPrep.SAMPLE_RATE / 4) return@awaitUse null // < 250 ms of speech: nothing to hear
        val t0 = System.currentTimeMillis()
        val seconds = speech.size.toDouble() / AudioPrep.SAMPLE_RATE
        val (text, lang) = if (e.ctc != null) {
            val hi = decodeCtc(e.ctc, speech)
            if (soundsHindi(hi, seconds)) hi to "hi" else decode(e, speech, pad = false).first to "en"
        } else {
            decode(e, speech, pad = true)
        }
        val cleaned = text.trim().takeIf { it.isNotEmpty() && !isHallucination(it) } ?: return@awaitUse null
        Transcript(cleaned, lang, speech.size * 1000L / AudioPrep.SAMPLE_RATE, System.currentTimeMillis() - t0)
    }

    /** Builds all engines. If one step fails, the ones built before it are released, so nothing native leaks. */
    private fun loadEngines(dir: Path): AsrEngines = buildOrClose { cleanup ->
        ModelRuntime.loadSherpa()
        val size = whisperSize(dir)
        val ctcModel = dir.resolve("ctc-model.int8.onnx")
        val ctc = if (Files.exists(ctcModel)) {
            cleanup.track(
                OfflineRecognizer(
                    OfflineRecognizerConfig.builder().setOfflineModelConfig(
                        OfflineModelConfig.builder()
                            .setDolphin(OfflineDolphinModelConfig.builder().setModel(ctcModel.toString()).build())
                            .setTokens(dir.resolve("ctc-tokens.txt").toString()).setNumThreads(THREADS).build(),
                    ).build(),
                ),
                OfflineRecognizer::release,
            )
        } else {
            null
        }
        val whisper = cleanup.track(recognizer(dir, size, language = if (ctc != null) "en" else ""), OfflineRecognizer::release)
        val vad = cleanup.track(
            Vad(
                VadModelConfig.builder().setSileroVadModelConfig(
                    SileroVadModelConfig.builder().setModel(dir.resolve("silero_vad.onnx").toString())
                        .setThreshold(0.5f).setMinSilenceDuration(0.3f).setMinSpeechDuration(0.2f)
                        .setMaxSpeechDuration(15f).setWindowSize(512).build(),
                ).setSampleRate(AudioPrep.SAMPLE_RATE).setNumThreads(1).build(),
            ),
            Vad::release,
        )
        AsrEngines(ctc, whisper, vad, hexTokens = Files.exists(dir.resolve("HEX_TOKENS")), size = size)
    }

    private fun decodeCtc(r: OfflineRecognizer, x: FloatArray): String {
        val s = r.createStream()
        try {
            s.acceptWaveform(x, AudioPrep.SAMPLE_RATE)
            r.decode(s)
            return r.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    /** [pad]: room for Devanagari's many byte tokens (WhisperText.padForBudget); English doesn't need it. */
    private fun decode(e: AsrEngines, x: FloatArray, pad: Boolean): Pair<String, String> {
        val s = e.whisper.createStream()
        try {
            s.acceptWaveform(if (pad) WhisperText.padForBudget(x) else x, AudioPrep.SAMPLE_RATE)
            e.whisper.decode(s)
            val res = e.whisper.getResult(s)
            val text = if (e.hexTokens) WhisperText.fromHex(res.text) else res.text
            return text to res.lang.trim('<', '|', '>', ' ')
        } finally {
            s.release()
        }
    }

    /** Keeps only the speech Silero found (plus its own padding), so Whisper never sees long silence. */
    private fun trim(v: Vad, x: FloatArray): FloatArray = synchronized(v) {
        v.reset()
        var i = 0
        while (i + 512 <= x.size) { v.acceptWaveform(x.copyOfRange(i, i + 512)); i += 512 }
        v.flush()
        val parts = mutableListOf<FloatArray>()
        while (!v.empty()) { parts += v.front().samples; v.pop() }
        joinWithGaps(parts, AudioPrep.SAMPLE_RATE / 10) // 100 ms between pieces
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

    private fun locate(): Path? {
        val cwd = Path.of(System.getProperty("user.dir"))
        val candidates = listOfNotNull(
            System.getenv("PEBBLE_ASR_DIR")?.let { Path.of(it) },
            ModelChecksums.trustedUserDir(DatabaseFactory.defaultDataDir().toPath(), "asr", cache),
            System.getProperty("compose.application.resources.dir")?.let { Path.of(it).resolve("models/asr") },
            cwd.resolve("../brain/models/asr-voice").normalize(),
            cwd.resolve("brain/models/asr-voice").normalize(),
        )
        return candidates.firstOrNull { d -> Files.exists(d.resolve("silero_vad.onnx")) && runCatching { whisperSize(d) }.isSuccess }
    }

    companion object {
        private const val THREADS = 4

        /** [parts] end to end with [gap] silent samples between them, in one array (no boxing: this runs per command). */
        fun joinWithGaps(parts: List<FloatArray>, gap: Int): FloatArray {
            if (parts.isEmpty()) return FloatArray(0)
            val out = FloatArray(parts.sumOf { it.size } + gap * (parts.size - 1))
            var at = 0
            parts.forEachIndexed { k, p ->
                if (k > 0) at += gap // a new FloatArray is all 0f: the gap is already silence
                System.arraycopy(p, 0, out, at, p.size)
                at += p.size
            }
            return out
        }

        /** The Whisper size in [dir], from its token file name (base-tokens.txt → "base"). */
        fun whisperSize(dir: Path): String = Files.list(dir).use { files ->
            files.map {
                it.fileName.toString()
            }.filter { it.endsWith("-tokens.txt") && it != "ctc-tokens.txt" }.findFirst().get().removeSuffix("-tokens.txt")
        }

        /**
         * Dolphin writes Hindi/Hinglish speech in Devanagari. For English speech it returns nothing or
         * garbled Latin ("terely nightf the lowlam…"), which is Whisper's cue to take over.
         */
        fun soundsHindi(dolphinText: String, speechSeconds: Double): Boolean {
            val deva = dolphinText.count { it in 'ऀ'..'ॿ' && it.isLetter() }
            val latin = dolphinText.count { it in 'a'..'z' || it in 'A'..'Z' }
            return deva >= maxOf(2.0, speechSeconds * 1.5) && deva > latin
        }

        /** Whisper's classic inventions on near-silence (it was trained on subtitled video). */
        private val hallucinations = listOf("thank you for watching", "thanks for watching", "please subscribe", "[music]", "(music)")

        fun isHallucination(text: String): Boolean {
            val t = text.lowercase().trim()
            return hallucinations.any { t == it || (t.length < 40 && it in t) }
        }
    }
}
