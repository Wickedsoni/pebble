package dev.pebble.desktop.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class WhisperTextTest {
    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test
    fun lettersSplitAcrossTokensSurvive() {
        // "पर्यावरण" as Whisper may emit it: one letter's 3 bytes split over two tokens.
        val bytes = hex(" पर्यावरण")
        val tokens = listOf(bytes.take(4), bytes.drop(4).take(6), bytes.drop(10))
        assertEquals("पर्यावरण", WhisperText.fromHex(tokens.joinToString("")))
    }

    @Test
    fun englishAndMixedText() {
        assertEquals("kal 5 baje DBMS viva", WhisperText.fromHex(hex(" kal 5 baje DBMS viva")))
        assertEquals("not hex at all", WhisperText.fromHex("not hex at all"))
    }

    @Test
    fun shortClipsArePaddedForTheTokenBudget() {
        val fiveSeconds = FloatArray(5 * 16_000) { 0.1f }
        val padded = WhisperText.padForBudget(fiveSeconds)
        assertEquals(((5 * 2.5 + 1) * 16_000).toInt(), padded.size) // 2.5x the speech + 1 s
        assertEquals(0.1f, padded[5 * 16_000 - 1])
        assertEquals(0f, padded.last(), "padding is silence")
        assertEquals(29 * 16_000, WhisperText.padForBudget(FloatArray(20 * 16_000)).size, "capped below Whisper's 30 s window")
    }
}
