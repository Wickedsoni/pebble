package dev.pebble.desktop.voice

import ai.onnxruntime.OrtEnvironment
import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Speech-recognition evaluation (plan 3.4), on the same runtime the app uses.
 *
 *   ./gradlew :desktopApp:asrEval -Pclips=30
 *
 * Clips: FLEURS Hindi dev (read speech, CC BY 4.0) from brain/data/raw/fleurs_hi — a stand-in until
 * brain/eval/voice_v1 holds your own recordings. Noise is made here, so no noise licence is needed:
 * pink noise (fan / AC) and babble (four other FLEURS speakers mixed = chatter), at 5 and 10 dB SNR.
 * Each condition runs raw and through GTCRN, because denoising before Whisper can *hurt*.
 * Writes brain/models/asr/asr-eval.json and prints a WER / CER table.
 */
fun main(args: Array<String>) {
    val brain = Path.of(args.getOrElse(0) { "../brain" }).toAbsolutePath().normalize()
    val n = args.getOrElse(1) { "30" }.toInt()
    val models = args.getOrElse(2) { "base,small" }.split(',')
    OrtEnvironment.getEnvironment() // the intent model's runtime loads first (see VoiceSpikeTest)
    LibraryUtils.load()

    val fleurs = brain.resolve("data/raw/fleurs_hi")
    val clips = Files.readAllLines(fleurs.resolve("dev.tsv")).mapNotNull { line ->
        val c = line.split('\t')
        val wav = fleurs.resolve("dev").resolve(c[1])
        if (c.size > 3 && Files.exists(wav)) wav to c[3] else null
    }.distinctBy { it.second }
    val rng = Random(0)
    val eval = clips.shuffled(rng).take(n)
    val babbleSources = clips.filter { it !in eval }.shuffled(rng).take(4).map { WaveReader(it.first.toString()).samples }
    println("ASR eval: ${eval.size} FLEURS hi clips, models $models")

    val asr = brain.resolve("models/asr")
    val denoiser = OfflineSpeechDenoiser(
        OfflineSpeechDenoiserConfig.builder().setModel(
            OfflineSpeechDenoiserModelConfig.builder()
                .setGtcrn(OfflineSpeechDenoiserGtcrnModelConfig.builder().setModel(asr.resolve("gtcrn_simple.onnx").toString()).build())
                .setNumThreads(2).build(),
        ).build(),
    )

    val conditions = listOf(
        "clean" to null,
        "pink 10dB" to ("pink" to 10.0),
        "pink 5dB" to ("pink" to 5.0),
        "babble 10dB" to ("babble" to 10.0),
        "babble 5dB" to ("babble" to 5.0),
    )
    val results = mutableListOf<String>()
    // Every transcript, for script-aware scoring in brain/ (Whisper often writes Hindi in Roman letters).
    val dump = Files.newBufferedWriter(asr.resolve("asr-hyps.jsonl"))
    fun js(x: String) = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    for (size in models) {
        val dir = asr.resolve("sherpa-onnx-whisper-$size")
        val recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder().setOfflineModelConfig(
                OfflineModelConfig.builder().setWhisper(
                    OfflineWhisperModelConfig.builder()
                        .setEncoder(dir.resolve("$size-encoder.int8.onnx").toString())
                        .setDecoder(dir.resolve("$size-decoder.int8.onnx").toString())
                        .setLanguage("hi").setTask("transcribe").build(),
                ).setTokens(dir.resolve("$size-tokens.txt").toString()).setNumThreads(4).build(),
            ).setDecodingMethod("greedy_search").build(),
        )
        for ((cond, noise) in conditions) {
            for (denoise in listOf(false, true)) {
                if (cond == "clean" && denoise && size != models.first()) continue
                var errW = 0; var refW = 0; var errC = 0; var refC = 0; var audioMs = 0L; var decodeMs = 0L
                val noiseRng = Random(1)
                for ((wav, ref) in eval) {
                    val w = WaveReader(wav.toString())
                    var x = w.samples
                    if (noise != null) {
                        val n = if (noise.first == "pink") pink(x.size, noiseRng) else babble(x.size, babbleSources, noiseRng)
                        x = mix(x, n, noise.second)
                    }
                    if (denoise) x = denoiser.run(x, w.sampleRate).samples
                    val s = recognizer.createStream()
                    s.acceptWaveform(x, w.sampleRate)
                    val t0 = System.nanoTime()
                    recognizer.decode(s)
                    decodeMs += (System.nanoTime() - t0) / 1_000_000
                    val hyp = recognizer.getResult(s).text
                    s.release()
                    audioMs += x.size * 1000L / w.sampleRate
                    dump.write(
                        """{"model":${js(size)},"condition":${js(cond)},"denoise":$denoise,""" +
                            """"ref":${js(ref)},"hyp":${js(hyp)}}""",
                    )
                    dump.newLine()
                    val r = norm(ref); val h = norm(hyp)
                    if (System.getenv("ASR_SHOW") != null && cond == "clean" && !denoise) { println("  REF: $r"); println("  HYP: $h") }
                    val rw = r.split(' ').filter { it.isNotEmpty() }
                    val hw = h.split(' ').filter { it.isNotEmpty() }
                    errW += edits(rw, hw)
                    refW += rw.size
                    errC += edits(r.replace(" ", "").map { it.toString() }, h.replace(" ", "").map { it.toString() })
                    refC += r.replace(" ", "").length
                }
                val line = "whisper-%-5s %-12s %-8s WER %5.1f%%  CER %5.1f%%  RTF %.2f".format(
                    size,
                    cond,
                    if (denoise) "+gtcrn" else "raw",
                    100.0 * errW / refW,
                    100.0 * errC / refC,
                    decodeMs.toDouble() / audioMs,
                )
                println(line)
                results += """{"model":"$size","condition":"$cond","denoise":$denoise,""" +
                    """"wer":${errW.toDouble() / refW},"cer":${errC.toDouble() / refC},"rtf":${decodeMs.toDouble() / audioMs}}"""
            }
        }
        recognizer.release()
    }
    denoiser.release()
    dump.close()
    Files.writeString(asr.resolve("asr-eval.json"), "[\n" + results.joinToString(",\n") + "\n]\n")
}

/** Lowercase, Devanagari danda and punctuation out, whitespace collapsed (FLEURS is normalised the same way). */
internal fun norm(s: String): String =
    s.lowercase().replace(Regex("""[\p{Punct}।॥“”‘’]"""), " ").replace(Regex("""\s+"""), " ").trim()

/** Levenshtein distance between token lists. */
internal fun <T> edits(a: List<T>, b: List<T>): Int {
    var prev = IntArray(b.size + 1) { it }
    for (i in 1..a.size) {
        val cur = IntArray(b.size + 1)
        cur[0] = i
        for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.size]
}

private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { s, v -> s + v * v } / x.size.coerceAtLeast(1))

/** Speech + noise scaled to [snrDb]. */
internal fun mix(speech: FloatArray, noise: FloatArray, snrDb: Double): FloatArray {
    val gain = rms(speech) / (rms(noise).coerceAtLeast(1e-9) * Math.pow(10.0, snrDb / 20))
    return FloatArray(speech.size) { (speech[it] + gain * noise[it]).toFloat().coerceIn(-1f, 1f) }
}

/** Pink (1/f) noise, Voss–McCartney: sounds like a fan or air conditioner. */
internal fun pink(n: Int, rng: Random): FloatArray {
    val rows = DoubleArray(12)
    var running = 0.0
    return FloatArray(n) { i ->
        val k = Integer.numberOfTrailingZeros(i + 1).coerceAtMost(rows.size - 1)
        running -= rows[k]; rows[k] = rng.nextDouble(-1.0, 1.0); running += rows[k]
        (running / rows.size).toFloat()
    }
}

/** Several other speakers at once, offset at random: chatter in a room. */
internal fun babble(n: Int, sources: List<FloatArray>, rng: Random): FloatArray {
    val out = FloatArray(n)
    for (src in sources) {
        val off = rng.nextInt(src.size)
        for (i in 0 until n) out[i] += src[(off + i) % src.size]
    }
    return out
}
