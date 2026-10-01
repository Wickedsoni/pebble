package dev.pebble.desktop.brain

import dev.pebble.core.brain.Understanding
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Owns the command model's lifecycle so it costs nothing when unused:
 *  - finds the model folder (env `PEBBLE_MODELS_DIR`, then `%APPDATA%\Pebble\models\intent`,
 *    then the dev build in `brain/models/intent-v0-pruned`)
 *  - verifies the ONNX file against `manifest.json`'s SHA-256 before trusting it
 *  - loads lazily in the background ([warmUp]); [understand] never blocks on loading
 *  - frees it (~60 MB of RAM) after [idleMillis] without use
 * If anything is missing or wrong, [understand] returns null and the router uses rules only.
 */
class ModelManager(private val scope: CoroutineScope, private val idleMillis: Long = 10 * 60_000L) : Understanding {
    @Volatile private var model: OnnxIntentModel? = null
    @Volatile private var lastUse = 0L
    @Volatile var status: String = "not loaded"
        private set
    private var loading: Job? = null

    val modelDir: Path? by lazy { locate() }

    /** Start loading if needed (e.g. when the quick-add bar opens). Cheap to call repeatedly. */
    @Synchronized
    fun warmUp() {
        lastUse = System.currentTimeMillis()
        if (model != null || loading?.isActive == true) return
        val dir = modelDir ?: run { status = "no model found"; return }
        loading = scope.launch(Dispatchers.IO) {
            status = "loading"
            val ok = verify(dir)
            if (!ok) { status = "checksum mismatch — not loaded"; return@launch }
            val t0 = System.currentTimeMillis()
            model = runCatching { OnnxIntentModel(dir) }.onFailure { status = "load failed: ${it.message}" }.getOrNull()
            if (model != null) {
                status = "ready (${System.currentTimeMillis() - t0} ms load)"
                watchIdle()
            }
        }
    }

    override fun understand(text: String): Understood? {
        val m = model ?: run { warmUp(); return null }
        lastUse = System.currentTimeMillis()
        return runCatching { m.understand(text) }.getOrNull()
    }

    private fun watchIdle() = scope.launch {
        while (isActive && model != null) {
            delay(60_000)
            if (System.currentTimeMillis() - lastUse > idleMillis) unload()
        }
    }

    @Synchronized
    private fun unload() {
        model?.close()
        model = null
        status = "unloaded (idle)"
    }

    private fun locate(): Path? {
        val candidates = listOfNotNull(
            System.getenv("PEBBLE_MODELS_DIR")?.let { Path.of(it) },
            DatabaseFactory.defaultDataDir().toPath().resolve("models/intent"),
            Path.of(System.getProperty("user.dir")).resolve("../brain/models/intent-v0-pruned").normalize(),
            Path.of(System.getProperty("user.dir")).resolve("brain/models/intent-v0-pruned").normalize(),
        )
        return candidates.firstOrNull { Files.exists(it.resolve("intent.int8.onnx")) && Files.exists(it.resolve("tokenizer/tokenizer.json")) }
    }

    /** Checks the model file's SHA-256 against manifest.json (next to it, or one level up in the dev layout). */
    private fun verify(dir: Path): Boolean {
        val manifest = listOf(dir.resolve("manifest.json"), dir.parent.resolve("manifest.json")).firstOrNull(Files::exists)
            ?: return true // hand-placed model without a manifest: allowed, but nothing to check against
        val entry = Json.parseToJsonElement(Files.readString(manifest)).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "intent" } ?: return true
        val want = entry.getValue("files").jsonObject.getValue("model").jsonObject.getValue("sha256").jsonPrimitive.content
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(dir.resolve("intent.int8.onnx")).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == want
    }
}
