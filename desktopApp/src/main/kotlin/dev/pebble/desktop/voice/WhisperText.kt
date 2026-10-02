package dev.pebble.desktop.voice

/**
 * Whisper's tokens are byte-level: one Devanagari letter (3 UTF-8 bytes) can span two tokens, and
 * sherpa-onnx's Java API converts each token to a string on its own, so half letters vanish
 * ("पर्यावरण" → "र्यारन"). Our packaged models (brain/.../prepare_asr.py, marker file HEX_TOKENS)
 * spell every token as the hex of its bytes; [fromHex] joins them and decodes UTF-8 once.
 */
object WhisperText {
    fun fromHex(hex: String): String {
        val h = hex.filter { it.isLetterOrDigit() }.lowercase()
        if (h.length % 2 != 0 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return hex // not hex: leave as is
        val bytes = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) + Character.digit(h[2 * it + 1], 16)).toByte() }
        return String(bytes, Charsets.UTF_8).trim()
    }
}
