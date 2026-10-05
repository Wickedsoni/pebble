package dev.pebble.desktop.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Checks a model folder against `manifest.json` (next to it, or one level up — the installed and dev
 * layouts) before anything is loaded: every file the manifest lists for [name] must match its SHA-256.
 * A hand-placed model without a manifest entry is allowed (nothing to check against).
 * With a [VerifiedModelCache], files that passed before and didn't change are not hashed again.
 */
object ModelChecksums {
    fun verify(dir: Path, name: String, cache: VerifiedModelCache? = null): Boolean {
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
