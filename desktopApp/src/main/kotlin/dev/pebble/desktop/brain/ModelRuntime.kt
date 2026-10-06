package dev.pebble.desktop.brain

import ai.onnxruntime.OrtEnvironment
import com.k2fsa.sherpa.onnx.LibraryUtils
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.voice.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * All on-device models, with one shared [VerifiedModelCache] (`models-verified.json` in [dataDir]):
 * the command model ([intent]) and the speech models ([speech]). Nothing loads until it's needed.
 */
class ModelRuntime(scope: CoroutineScope, dataDir: Path, idleMillis: Long = 10 * 60_000L) {
    /** Where signed packs are installed (WP D2). */
    val packsDir: Path = dataDir.resolve("models")

    // A chat server left by a Pebble that did not exit cleanly would lock the files of a pack install.
    init {
        // Only when a chat pack install or removal waits: then applyStaged needs the files unlocked.
        if (Files.exists(packsDir.resolve("chat.staged")) || Files.exists(packsDir.resolve("chat.remove"))) {
            runCatching { LocalChat.killLeftovers(packsDir.resolve("chat").resolve("llama-server.exe"), Logger.None) }
        }
    }

    val cache = VerifiedModelCache(dataDir.resolve("models-verified.json"))

    /** Pack installs and removals staged in the last run, done now: no model is loaded yet. New files go in [cache] as checked. */
    val packChangesAtStart: List<String> = runCatching {
        ModelPack.applyStaged(packsDir, cache)
    }.getOrElse { listOf("failed: ${it.message}") }

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
            NativeLibs.configureOrt()
            OrtEnvironment.getEnvironment()
            NativeLibs.configureSherpa()
            LibraryUtils.load()
        }
    }
}
