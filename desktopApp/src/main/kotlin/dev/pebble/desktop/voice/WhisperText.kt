package dev.pebble.desktop.voice

/**
 * Whisper's tokens are byte-level: one Devanagari letter (3 UTF-8 bytes) can span two tokens, and
 * sherpa-onnx's Java API converts each token to a string on its own, so half letters vanish
 * ("पर्यावरण" → "र्यारन"). Our packaged models (brain/.../prepare_asr.py, marker file HEX_TOKENS)
 * spell every token as the hex of its bytes; [fromHex] joins them and decodes UTF-8 once.
 */
object WhisperText {
    /**
     * sherpa-onnx lets Whisper write at most 6 tokens per second of *input* audio — fine for English,
     * but spoken Hindi in Devanagari takes ~10-15 byte tokens per second, so sentences were cut off
     * mid-word. Trailing silence raises the budget, but the encoder's cost grows with input length (and
     * long silence invites made-up text), so pad only what's needed: about 2.5x the speech, +1 s.
     */
    fun padForBudget(x: FloatArray, rate: Int = AudioPrep.SAMPLE_RATE, factor: Double = 2.5, maxSeconds: Double = 29.0): FloatArray {
        val target = minOf(x.size * factor + rate, maxSeconds * rate).toInt()
        return if (x.size >= target) x else x.copyOf(target)
    }

    fun fromHex(hex: String): String {
        val h = hex.filter { it.isLetterOrDigit() }.lowercase()
        if (h.length % 2 != 0 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return hex // not hex: leave as is
        val bytes = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) + Character.digit(h[2 * it + 1], 16)).toByte() }
        return String(bytes, Charsets.UTF_8).trim()
    }
}
