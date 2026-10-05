package dev.pebble.desktop.brain

import dev.pebble.core.brain.Embedding
import dev.pebble.core.brain.Understanding
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owns the command model's lifecycle so it costs nothing when unused:
 *  - finds the model folder: env `PEBBLE_MODELS_DIR`, then `%APPDATA%\Pebble\models\intent` (a newer model
 *    placed there wins), then the copy bundled in the installer, then the dev build that
 *    `brain/models/manifest.json` names (e.g. `brain/models/intent-v0-pruned`)
 *  - verifies the ONNX file against `manifest.json`'s SHA-256 before trusting it
 *  - loads lazily in the background ([warmUp]); [understand] never blocks on loading
 *  - frees it (~60 MB of RAM) after [idleMillis] without use
 * If anything is missing or wrong, [understand] returns null and the router uses rules only.
 */
class ModelManager(
    scope: CoroutineScope,
    idleMillis: Long = 10 * 60_000L,
    /** Skips re-hashing files that passed before (null: hash every load, as tests and the dev tools do). */
    private val cache: VerifiedModelCache? = null,
) : Understanding {
    private val lazy = LazyModel(
        name = "intent",
        scope = scope,
        idleMillis = idleMillis,
        locate = ::locate,
        verify = { ModelChecksums.verify(it, "intent", cache) },
        load = ::OnnxIntentModel,
        onLoadAttempt = ::log,
    )

    /** Status for logs and diagnostics ("ready (412 ms load)", "checksum mismatch — not loaded", …). */
    val status: String get() = lazy.status.value.toString()

    val statusFlow: StateFlow<ModelStatus> get() = lazy.status

    val modelDir: Path? get() = lazy.dir

    /** Start loading if needed (e.g. when the quick-add bar opens). Cheap to call repeatedly. */
    fun warmUp() = lazy.warmUp()

    /** Waits for a pending load (tests and diagnostics; the app never blocks on this). */
    suspend fun awaitLoaded(): Boolean = lazy.join()

    /** The command model's version (see [ModelChecksums.version]); null when there is no model. */
    val version: String? by lazy { modelDir?.let { runCatching { ModelChecksums.version(it, "intent", "intent.int8.onnx") }.getOrNull() } }

    /** The sentence embedding of [text] if the model is loaded now; never loads it (background indexing). */
    fun embedIfLoaded(text: String): Embedding? = lazy.getOrNull()?.let { m -> runCatching { m.understand(text)?.embedding }.getOrNull() }

    /** The sentence embedding of [text], loading the model first if needed (a search you asked for). */
    suspend fun embedLoading(
        text: String,
    ): Embedding? = lazy.await()?.let { m -> runCatching { m.understand(text)?.embedding }.getOrNull() }

    override fun understand(text: String): Understood? {
        val m = lazy.getOrNull() ?: run { lazy.warmUp(); return null }
        return runCatching { m.understand(text) }.getOrNull()
    }

    /** One line per load in `%APPDATA%\Pebble\brain.log`: which model, from where, and how it went. */
    private fun log(dir: Path, status: ModelStatus) {
        runCatching {
            val file = DatabaseFactory.defaultDataDir().toPath().resolve("brain.log")
            if (Files.exists(file) && Files.size(file) > 64_000) Files.delete(file)
            Files.writeString(
                file,
                "${java.time.LocalDateTime.now().withNano(0)}  $status  $dir\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    private fun locate(): Path? {
        val cwd = Path.of(System.getProperty("user.dir"))
        val candidates = listOfNotNull(
            System.getenv("PEBBLE_MODELS_DIR")?.let { Path.of(it) },
            DatabaseFactory.defaultDataDir().toPath().resolve("models/intent"),
            // Set by Compose Desktop in the installed app (and by `run`): <install>/app/resources.
            System.getProperty("compose.application.resources.dir")?.let { Path.of(it).resolve("models/intent") },
        ) + listOf(cwd.resolve("../brain/models"), cwd.resolve("brain/models")).mapNotNull { devModel(it.normalize()) }
        return candidates.firstOrNull {
            Files.exists(it.resolve("intent.int8.onnx")) && Files.exists(it.resolve("tokenizer/tokenizer.json"))
        }
    }

    /** The dev-layout model folder that `<models>/manifest.json` points at. */
    private fun devModel(models: Path): Path? {
        val manifest = models.resolve("manifest.json").takeIf(Files::exists) ?: return null
        val path =
            runCatching {
                modelEntry(manifest)?.getValue("files")?.jsonObject?.getValue("model")?.jsonObject?.getValue("path")?.jsonPrimitive?.content
            }
                .getOrNull() ?: return null
        return models.resolve(path).parent
    }

    private fun modelEntry(manifest: Path) = Json.parseToJsonElement(Files.readString(manifest)).jsonObject.getValue("models").jsonArray
        .map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "intent" }
}
