package dev.pebble.desktop.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPrepTest {
    private fun tone(hz: Double, seconds: Double = 1.0, amp: Float = 0.5f) =
        FloatArray((16_000 * seconds).toInt()) { (amp * sin(2 * PI * hz * it / 16_000)).toFloat() }

    @Test
    fun highPassRemovesRumbleAndKeepsSpeechBand() {
        val dc = FloatArray(16_000) { 0.3f }
        assertTrue(AudioPrep.rms(AudioPrep.highPass(dc).copyOfRange(4_000, 16_000)) < 0.01, "DC offset gone")
        val voice = tone(300.0)
        assertTrue(AudioPrep.rms(AudioPrep.highPass(voice)) > 0.9 * AudioPrep.rms(voice), "300 Hz speech kept")
        assertTrue(AudioPrep.rms(AudioPrep.highPass(tone(20.0))) < 0.35 * AudioPrep.rms(tone(20.0)), "20 Hz rumble cut")
    }

    @Test
    fun quietAndLoudMicsEndUpAlike() {
        for (amp in listOf(0.01f, 0.2f)) {
            val out = AudioPrep.normalize(tone(300.0, amp = amp))
            assertEquals(-20.0, 20 * log10(AudioPrep.rms(out)), 0.5)
            assertTrue(out.maxOf { abs(it) } <= 0.95f)
        }
        val silence = FloatArray(16_000)
        assertTrue(AudioPrep.normalize(silence).all { it == 0f }, "silence is not amplified into noise")
    }

    @Test
    fun whisperInventionsAreDropped() {
        assertTrue(SpeechRecognizer.isHallucination("Thank you for watching!"))
        assertFalse(SpeechRecognizer.isHallucination("thank you pebble"))
        assertFalse(SpeechRecognizer.isHallucination("कल सुबह सात बजे उठा देना"))
    }

    @Test
    fun theMicIsClosedUnlessYouAreTalking() {
        val mic = MicCapture()
        assertFalse(mic.isOpen)
        if (mic.start()) { // only on machines with a microphone
            assertTrue(mic.isOpen)
            mic.stop()
        }
        assertFalse(mic.isOpen, "stop() always releases the microphone")
    }

    @Test
    fun englishSpeechGoesToWhisper() {
        assertTrue(SpeechRecognizer.soundsHindi("मेजर किन खाना भूमध्य सागर", 3.0))
        assertFalse(SpeechRecognizer.soundsHindi("terely nightf the lowlam light the", 3.0), "garbled Latin = English speech")
        assertFalse(SpeechRecognizer.soundsHindi("", 2.0))
        assertFalse(SpeechRecognizer.soundsHindi("हाँ", 4.0), "two letters for four seconds of speech: Dolphin missed it")
    }

    @Test
    fun microphoneSwitchedOffMeansTheMicNeverOpens() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val voice = VoiceInput(SpeechRecognizer(scope), scope, micAllowed = { false })
        assertFalse(voice.start(), "no recording when the Privacy switch is off")
        assertFalse(voice.isListening)
        assertTrue((voice.state.value as VoiceInput.State.Failed).message.contains("Microphone is off"))
    }
}
