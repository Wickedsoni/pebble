package dev.pebble.desktop.voice

import dev.pebble.core.brain.VoiceSample
import dev.pebble.core.brain.VoiceSampleRepository
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.settings.SettingsRepository.Keys
import java.nio.file.Files
import java.nio.file.Path

/** Voice clips you corrected (opt-in): kept as audio + your text, the best data for tuning speech to your voice. */
class VoiceCorrectionService(
    private val voice: VoiceInput,
    private val speech: SpeechRecognizer,
    private val settings: SettingsRepository,
    val samples: VoiceSampleRepository,
    private val clock: () -> Long,
    /** Where kept voice clips go (tests point it elsewhere). */
    var dir: Path,
) {
    /**
     * After a voice command runs: if you edited what Whisper heard and you've opted in, keep the clip and
     * your text — the best possible data for tuning speech recognition to your voice. Otherwise nothing.
     */
    fun noteCorrection(finalText: String) {
        val heard = voice.lastHeard ?: return
        val audio = voice.lastAudio ?: return
        if (finalText.trim() == heard.text.trim() || !settings.bool(Keys.KEEP_VOICE_CORRECTIONS, false)) return
        runCatching {
            val wav = dir.resolve("${clock()}.wav")
            writeWav(wav, audio)
            val model = speech.modelDir?.let { SpeechRecognizer.whisperSize(it) } ?: "?"
            samples.add(VoiceSample(wav.toString(), heard.text, finalText.trim(), "whisper-$model", clock()))
        }
    }

    /** Deletes every kept voice clip, files and rows. */
    fun clear() {
        samples.all().forEach { runCatching { Files.deleteIfExists(Path.of(it.wavPath)) } }
        samples.clear()
    }
}
