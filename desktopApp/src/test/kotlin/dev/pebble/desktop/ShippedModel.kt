package dev.pebble.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** The model folder brain/models/manifest.json ships (what the installer bundles), for tests. */
object ShippedModel {
    val brain: Path = Path.of(System.getProperty("user.dir")).parent.resolve("brain")

    val dir: Path by lazy {
        val manifest = brain.resolve("models/manifest.json")
        val path = Json.parseToJsonElement(Files.readString(manifest)).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == "intent" }
            .getValue("files").jsonObject.getValue("model").jsonObject.getValue("path").jsonPrimitive.content
        brain.resolve("models").resolve(path).parent
    }
}
