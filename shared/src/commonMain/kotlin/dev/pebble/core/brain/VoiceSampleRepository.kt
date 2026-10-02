package dev.pebble.core.brain

import dev.pebble.db.PebbleDatabase

data class VoiceSample(val wavPath: String, val asrText: String, val finalText: String, val asrModel: String, val atMillis: Long)

/** Voice clips you corrected (opt-in): what Whisper heard vs what you meant, for tuning it to your voice. */
class VoiceSampleRepository(private val db: PebbleDatabase) {
    private val q get() = db.brainQueries

    fun add(s: VoiceSample) {
        q.insertVoiceSample(s.wavPath, s.asrText, s.finalText, s.asrModel, s.atMillis)
    }

    fun all(): List<VoiceSample> = q.allVoiceSamples().executeAsList().map {
        VoiceSample(it.wav_path, it.asr_text, it.final_text, it.asr_model, it.at_millis)
    }

    fun count(): Long = q.voiceSampleCount().executeAsOne()

    /** Forgets the rows; the caller deletes the audio files (paths from [all] first). */
    fun clear() {
        q.deleteVoiceSamples()
    }
}
