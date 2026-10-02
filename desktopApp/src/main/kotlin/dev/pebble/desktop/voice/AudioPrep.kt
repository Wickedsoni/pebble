package dev.pebble.desktop.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cheap, safe clean-up before recognition (plan 3.1). Deliberately *not* a denoiser: removing noise
 * with a neural model can make Whisper worse, so that's only used where our own eval shows a gain.
 *  - [highPass] ~80 Hz: desk thumps, fan rumble and mic DC offset carry no speech.
 *  - [normalize] to about -20 dBFS with a peak limit: quiet laptop mics and loud headsets look alike.
 */
object AudioPrep {
    const val SAMPLE_RATE = 16_000

    fun prepare(x: FloatArray): FloatArray = normalize(highPass(x))

    /** First-order high-pass filter. */
    fun highPass(x: FloatArray, cutoffHz: Double = 80.0, rate: Int = SAMPLE_RATE): FloatArray {
        if (x.isEmpty()) return x
        val rc = 1.0 / (2 * PI * cutoffHz)
        val a = (rc / (rc + 1.0 / rate)).toFloat()
        val y = FloatArray(x.size)
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = a * (y[i - 1] + x[i] - x[i - 1])
        return y
    }

    /** Scale to [targetDbfs] RMS, but never past [peak]; silence is left alone. */
    fun normalize(x: FloatArray, targetDbfs: Double = -20.0, peak: Float = 0.95f): FloatArray {
        val rms = rms(x)
        if (rms < 1e-4) return x
        val target = Math.pow(10.0, targetDbfs / 20)
        val maxAbs = x.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = minOf(target / rms, peak / maxAbs.toDouble()).toFloat()
        return FloatArray(x.size) { x[it] * gain }
    }

    fun rms(x: FloatArray): Double = if (x.isEmpty()) 0.0 else sqrt(x.fold(0.0) { s, v -> s + v * v } / x.size)

    /** 0..1 meter value for the "listening" UI (log-ish so quiet speech still moves it). */
    fun level(x: FloatArray): Float {
        val db = 20 * kotlin.math.log10(rms(x).coerceAtLeast(1e-5))
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}
