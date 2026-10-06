package dev.pebble.desktop.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A closed Quick Add or a new press must never let an old transcription answer (QA round 1, R3-2). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputSessionTest {
    private class FakeMic : VoiceInput.Microphone {
        var onLevel: (Float) -> Unit = {}

        override fun start() = true

        override fun stop() = FloatArray(16_000)
    }

    private class FakeRecognizer : VoiceInput.Transcriber {
        val pending = ArrayList<CompletableDeferred<Transcript?>>()
        override val modelDir: Path? = Path.of("model")

        override fun warmUp() = Unit

        override suspend fun transcribe(samples: FloatArray): Transcript? {
            val d = CompletableDeferred<Transcript?>()
            pending.add(d)
            return d.await()
        }
    }

    private fun heard(text: String) = Transcript(text, "en", 1000, 5)

    private fun setup(test: TestScope): Triple<VoiceInput, FakeRecognizer, FakeMic> {
        val rec = FakeRecognizer()
        val mic = FakeMic()
        val voice = VoiceInput(rec, test, { l -> mic.also { it.onLevel = l } })
        return Triple(voice, rec, mic)
    }

    @Test
    fun cancelDuringTranscribingNeverBecomesHeard() = runTest(StandardTestDispatcher()) {
        val (voice, rec, _) = setup(this)
        assertTrue(voice.start())
        voice.stop()
        runCurrent()
        assertEquals(VoiceInput.State.Transcribing, voice.state.value)

        voice.cancel() // Quick Add closed while Whisper was loading
        rec.pending.forEach { it.complete(heard("delete everything")) }
        advanceUntilIdle()

        assertEquals(VoiceInput.State.Idle, voice.state.value)
        assertNull(voice.lastHeard)
    }

    @Test
    fun aNewSessionIgnoresTheOldResult() = runTest(StandardTestDispatcher()) {
        val (voice, rec, _) = setup(this)
        voice.start()
        voice.stop()
        runCurrent()
        voice.start() // pressed again before the first clip was understood
        voice.stop()
        runCurrent()
        assertEquals(2, rec.pending.size)

        rec.pending[0].complete(heard("old"))
        runCurrent()
        assertEquals(VoiceInput.State.Transcribing, voice.state.value, "the old result is dropped")

        rec.pending[1].complete(heard("new"))
        runCurrent()
        assertEquals("new", assertIs<VoiceInput.State.Heard>(voice.state.value).transcript.text)
    }

    @Test
    fun aLateLevelDoesNotOverwriteTranscribing() = runTest(StandardTestDispatcher()) {
        val (voice, _, mic) = setup(this)
        voice.start()
        mic.onLevel(0.5f)
        assertEquals(VoiceInput.State.Listening(0.5f), voice.state.value)
        val oldLevel = mic.onLevel // the callback of the first session
        voice.stop()
        mic.onLevel(0.9f) // the capture thread was a moment late
        assertEquals(VoiceInput.State.Transcribing, voice.state.value)
        voice.cancel()
        voice.start() // a new session: Listening again
        oldLevel(0.7f) // a callback of the old session must not touch it
        assertEquals(VoiceInput.State.Listening(0f), voice.state.value)
        voice.cancel() // ends the transcription that never gets an answer
    }
}
