package dev.pebble.desktop.voice

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * Push-to-talk microphone: the line is opened by [start] and closed by [stop] — never left open,
 * so Pebble can't hear anything outside a press and costs nothing while idle.
 * 16 kHz mono 16-bit, what Whisper and Silero VAD expect; Windows resamples the device for us.
 */
class MicCapture(private val onLevel: (Float) -> Unit = {}) : VoiceInput.Microphone {
    private val format = AudioFormat(AudioPrep.SAMPLE_RATE.toFloat(), 16, 1, true, false)

    @Volatile private var line: TargetDataLine? = null

    @Volatile private var recording = false
    private var buffer = ByteArrayOutputStream()
    private var reader: Thread? = null

    override fun start(): Boolean = start(15)

    val isOpen: Boolean get() = line != null

    /** Opens the mic and starts buffering. Returns false if there's no usable microphone. */
    @Synchronized
    fun start(maxSeconds: Int): Boolean {
        if (line != null) return true
        val l = runCatching { AudioSystem.getTargetDataLine(format).apply { open(format); start() } }.getOrNull() ?: return false
        line = l
        recording = true
        buffer = ByteArrayOutputStream()
        val maxBytes = maxSeconds * AudioPrep.SAMPLE_RATE * 2
        reader = thread(isDaemon = true, name = "pebble-mic") {
            val chunk = ByteArray(1024) // 32 ms
            while (recording && buffer.size() < maxBytes) {
                val n = l.read(chunk, 0, chunk.size)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                onLevel(AudioPrep.level(toFloats(chunk, n)))
            }
        }
        return true
    }

    /** Closes the mic and returns what was recorded, as floats in -1..1. */
    @Synchronized
    override fun stop(): FloatArray {
        recording = false
        reader?.join(200)
        line?.run { stop(); close() }
        line = null
        val bytes = buffer.toByteArray()
        return toFloats(bytes, bytes.size)
    }

    private fun toFloats(b: ByteArray, n: Int): FloatArray =
        FloatArray(n / 2) { i -> ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xFF)).toShort() / 32768f }
}
