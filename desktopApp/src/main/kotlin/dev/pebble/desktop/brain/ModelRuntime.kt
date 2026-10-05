package dev.pebble.desktop.brain

import ai.onnxruntime.OrtEnvironment
import com.k2fsa.sherpa.onnx.LibraryUtils
import dev.pebble.desktop.voice.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * All on-device models, with one shared [VerifiedModelCache] (`models-verified.json` in [dataDir]):
 * the command model ([intent]) and the speech models ([speech]). Nothing loads until it's needed.
 */
class ModelRuntime(scope: CoroutineScope, dataDir: Path, idleMillis: Long = 10 * 60_000L) {
    val cache = VerifiedModelCache(dataDir.resolve("models-verified.json"))
    val intent = ModelManager(scope, idleMillis, cache)
    val speech = SpeechRecognizer(scope, idleMillis, cache)

    companion object {
        /**
         * The only place sherpa-onnx's native library is loaded. The command model's ONNX Runtime must be in the
         * process first: sherpa binds to it, not the other way round. In the reverse order the command model's
         * numbers shift (VoiceSpikeTest). Not done at start-up: ONNX Runtime costs memory, and the pet alone needs none.
         */
        @Synchronized
        fun loadSherpa() {
            OrtEnvironment.getEnvironment()
            LibraryUtils.load()
        }
    }
}
