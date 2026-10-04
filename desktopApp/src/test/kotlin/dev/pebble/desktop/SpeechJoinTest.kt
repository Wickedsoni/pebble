package dev.pebble.desktop

import dev.pebble.desktop.voice.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The speech pieces Silero keeps are joined with short silences between them, in one copy. */
class SpeechJoinTest {
    @Test
    fun piecesAreJoinedWithSilentGaps() {
        val joined = SpeechRecognizer.joinWithGaps(listOf(floatArrayOf(1f, 2f), floatArrayOf(3f), floatArrayOf(4f, 5f)), gap = 2)
        assertContentEquals(floatArrayOf(1f, 2f, 0f, 0f, 3f, 0f, 0f, 4f, 5f), joined)
    }

    @Test
    fun onePieceHasNoGapAndNothingIsEmpty() {
        assertContentEquals(floatArrayOf(1f, 2f), SpeechRecognizer.joinWithGaps(listOf(floatArrayOf(1f, 2f)), gap = 1_600))
        assertEquals(0, SpeechRecognizer.joinWithGaps(emptyList(), gap = 1_600).size)
    }
}
