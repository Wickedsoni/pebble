package dev.pebble.desktop

import dev.pebble.core.brain.Replies
import dev.pebble.core.quickadd.QuickCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import dev.pebble.core.brain.PebbleActions as A

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

/** The Pebble action a command performs, as eval files label it. */
internal fun actionOf(c: QuickCommand): String = when (c) {
    is QuickCommand.RemindAt, is QuickCommand.RemindIn -> A.REMIND
    is QuickCommand.AddNote -> A.ADD_NOTE
    is QuickCommand.RememberFact -> "remember_fact"
    is QuickCommand.LogWater -> "log_water"
    is QuickCommand.SetInterval -> "set_interval"
    QuickCommand.ShowUpcoming -> A.REMINDERS_QUERY
    QuickCommand.ShowNotes, is QuickCommand.SearchMemory -> A.NOTES_QUERY
    QuickCommand.TellTime -> A.TIME_QUERY
    is QuickCommand.Chitchat -> if (c.intent == Replies.LOW_MOOD) "mood" else A.CHITCHAT
    is QuickCommand.Unsupported -> A.OTHER
    is QuickCommand.OpenPage -> if (c.page == "notes") A.NOTE_REMOVE else A.REMINDER_REMOVE
}
