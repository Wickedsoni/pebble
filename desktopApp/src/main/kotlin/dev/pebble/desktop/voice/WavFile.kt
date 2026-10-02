package dev.pebble.desktop.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** 16 kHz mono PCM16 WAV, the format Whisper and the voice test set use. */
fun writeWav(path: Path, x: FloatArray, rate: Int = AudioPrep.SAMPLE_RATE) {
    val buf = ByteBuffer.allocate(44 + x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(x.size * 2)
    x.forEach { buf.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    Files.createDirectories(path.parent)
    Files.write(path, buf.array())
}
