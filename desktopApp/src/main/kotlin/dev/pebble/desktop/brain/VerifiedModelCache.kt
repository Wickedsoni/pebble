package dev.pebble.desktop.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Remembers model files that already passed their SHA-256 check (`models-verified.json` in the data dir):
 * path, size, last-modified time and hash. A file is hashed again only when its size or time changed, so
 * reloading the speech models after an idle unload no longer reads ~300 MB.
 *
 * Trust: the same as the model files in `%APPDATA%\Pebble\models` — whoever can rewrite this file can
 * already replace those models. A file changed in place with its old size and time kept is not noticed.
 */
class VerifiedModelCache(
    private val file: Path,
    private val hash: (Path) -> String = ModelChecksums::sha256,
) {
    private data class Entry(val size: Long, val lastModified: Long, val sha256: String)

    private val entries: MutableMap<String, Entry> by lazy { read() }

    /** True if [path] has the SHA-256 [expected]; hashes only when the file changed since it last matched. */
    @Synchronized
    fun matches(path: Path, expected: String): Boolean {
        if (!Files.exists(path)) return false
        val key = path.toAbsolutePath().normalize().toString()
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        entries[key]?.let { e ->
            if (e.size == size && e.lastModified == modified) return e.sha256 == expected
        }
        val actual = hash(path)
        if (actual != expected) return false
        entries[key] = Entry(size, modified, actual)
        write()
        return true
    }

    private fun read(): MutableMap<String, Entry> = runCatching {
        Json.parseToJsonElement(Files.readString(file)).jsonObject.getValue("files").jsonArray.associate { el ->
            val o = el.jsonObject
            o.getValue("path").jsonPrimitive.content to Entry(
                o.getValue("size").jsonPrimitive.long,
                o.getValue("lastModified").jsonPrimitive.long,
                o.getValue("sha256").jsonPrimitive.content,
            )
        }.toMutableMap()
    }.getOrElse { mutableMapOf() } // missing or unreadable: start empty, everything is hashed once

    private fun write() {
        runCatching {
            val json = JsonObject(
                mapOf(
                    "files" to JsonArray(
                        entries.map { (path, e) ->
                            JsonObject(
                                mapOf(
                                    "path" to JsonPrimitive(path),
                                    "size" to JsonPrimitive(e.size),
                                    "lastModified" to JsonPrimitive(e.lastModified),
                                    "sha256" to JsonPrimitive(e.sha256),
                                ),
                            )
                        },
                    ),
                ),
            )
            Files.createDirectories(file.parent)
            // Write a temp file and move it, so a crash mid-write never leaves half a cache.
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.toString())
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
