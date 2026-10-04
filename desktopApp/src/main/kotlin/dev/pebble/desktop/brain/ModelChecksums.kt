package dev.pebble.desktop.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Checks a model folder before anything is loaded:
 *  - a signed pack (`pack.json` in the folder): its signature and every file hash ([ModelPack.verifyInstalled])
 *  - otherwise `manifest.json` (next to it, or one level up — the installed and dev layouts): every file the
 *    manifest lists for [name] must match its SHA-256. A model without a manifest entry is allowed: the
 *    developer folders (env vars, `brain/models`). The user folder takes only signed packs ([trustedUserDir]).
 * With a [VerifiedModelCache], files that passed before and didn't change are not hashed again.
 */
object ModelChecksums {
    fun verify(dir: Path, name: String, cache: VerifiedModelCache? = null): Boolean {
        if (Files.exists(dir.resolve(ModelPack.MANIFEST))) return ModelPack.verifyInstalled(dir, name, cache) is ModelPack.Result.Ok
        val manifest = listOf(dir.resolve("manifest.json"), dir.parent.resolve("manifest.json")).firstOrNull(Files::exists)
            ?: return true
        val entry = Json.parseToJsonElement(Files.readString(manifest)).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == name } ?: return true
        return entry.getValue("files").jsonObject.values.all { f ->
            val o = f.jsonObject
            // Manifest paths start with the model's folder ("intent-v2-pruned/…"); the folder is [dir].
            val file = dir.resolve(o.getValue("path").jsonPrimitive.content.substringAfter('/'))
            val expected = o.getValue("sha256").jsonPrimitive.content
            if (cache != null) cache.matches(file, expected) else Files.exists(file) && sha256(file) == expected
        }
    }

    /**
     * The version the manifest gives the [name] model in [dir] ("v3-pruned"); for a hand-placed model without
     * a manifest entry, its file size and time — a new file means a new version either way.
     */
    fun version(dir: Path, name: String, file: String): String {
        ModelPack.installedManifest(dir)?.let { return "$name-${it.version}" }
        val fromManifest = runCatching {
            val manifest = listOf(dir.resolve("manifest.json"), dir.parent.resolve("manifest.json")).first(Files::exists)
            Json.parseToJsonElement(Files.readString(manifest)).jsonObject.getValue("models").jsonArray
                .map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == name }
                .getValue("version").jsonPrimitive.content
        }.getOrNull()
        if (fromManifest != null) return "$name-$fromManifest"
        val f = dir.resolve(file)
        return "$name-local-${Files.size(f)}-${Files.getLastModifiedTime(f).toMillis()}"
    }

    /**
     * `<dataDir>/models/<name>` if it holds a valid signed pack, else null (then the bundled model is used).
     * A model placed there by hand, or changed after install, is never loaded (WP D2).
     */
    fun trustedUserDir(dataDir: Path, name: String, cache: VerifiedModelCache?): Path? =
        dataDir.resolve("models").resolve(name).takeIf { ModelPack.verifyInstalled(it, name, cache) is ModelPack.Result.Ok }

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
