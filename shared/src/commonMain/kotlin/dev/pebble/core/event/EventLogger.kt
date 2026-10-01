package dev.pebble.core.event

import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Persists every bus event into `event_log` as JSON. */
class EventLogger(private val db: PebbleDatabase) {
    private val json = Json { encodeDefaults = true }

    fun log(event: PebbleEvent) {
        val encoded = json.encodeToJsonElement(PebbleEvent.serializer(), event).jsonObject
        val type = encoded["type"]?.jsonPrimitive?.content ?: "unknown"
        db.pebbleQueries.insertEvent(type, encoded.toString(), event.atMillis)
    }

    fun attach(bus: EventBus, scope: CoroutineScope): Job =
        scope.launch { bus.events.collect { log(it) } }
}
