package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.voice.Transcript
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Voice clips are kept only if you opted in *and* corrected what Pebble heard — and can be deleted. */
class VoiceCorrectionTest {
    private val app = PebbleApp(DatabaseFactory.inMemory()).apply {
        voiceDir = Files.createTempDirectory("pebble-voice")
        voice.lastAudio = FloatArray(16_000)
        voice.lastHeard = Transcript("kal paanch baje meeting", "hi", 1_000, 300)
    }

    @Test
    fun nothingIsKeptByDefault() {
        app.noteVoiceCorrection("kal paanch baje DBMS meeting")
        assertEquals(0, app.voiceSamples.count())
    }

    @Test
    fun onlyCorrectedClipsAreKeptWhenOptedIn() {
        app.settings.set(Keys.KEEP_VOICE_CORRECTIONS, "true")
        app.noteVoiceCorrection("kal paanch baje meeting") // heard right: nothing to learn
        assertEquals(0, app.voiceSamples.count())
        app.noteVoiceCorrection("kal paanch baje DBMS meeting")
        val s = app.voiceSamples.all().single()
        assertEquals("kal paanch baje meeting" to "kal paanch baje DBMS meeting", s.asrText to s.finalText)
        assertTrue(Files.exists(java.nio.file.Path.of(s.wavPath)))

        app.clearVoiceSamples()
        assertEquals(0, app.voiceSamples.count())
        assertTrue(Files.notExists(java.nio.file.Path.of(s.wavPath)), "delete removes the audio file too")
    }
}
