package dev.pebble.core.event

import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Persists every bus event into `event_log` as JSON, off the publisher's thread (often the UI thread):
 * the bus subscriber only hands each event to a queue, and one writer saves them in batches, one
 * transaction per batch. Nothing is lost: when the queue is full or closed, the event is written at once.
 */
class EventLogger(private val db: PebbleDatabase) {
    private val json = Json { encodeDefaults = true }

    /** A queue item: an event, or a marker that completes once everything before it is saved. */
    private sealed interface Item {
        class Event(val event: PebbleEvent) : Item

        class Flushed(val done: CompletableDeferred<Unit>) : Item
    }

    @kotlin.concurrent.Volatile private var queue: Channel<Item>? = null
    private var writer: Job? = null

    /** Writes one event now, on the caller's thread. */
    fun log(event: PebbleEvent) {
        val encoded = json.encodeToJsonElement(PebbleEvent.serializer(), event).jsonObject
        val type = encoded["type"]?.jsonPrimitive?.content ?: "unknown"
        db.pebbleQueries.insertEvent(type, encoded.toString(), event.atMillis)
    }

    /**
     * Saves [bus] events from now on. The subscriber runs unconfined (on the publisher's thread, so the order
     * is kept and nothing waits for a dispatch); the writer runs on [writerDispatcher] — one thread at a time.
     */
    fun attach(bus: EventBus, scope: CoroutineScope, writerDispatcher: CoroutineDispatcher, capacity: Int = 4096): Job {
        val q = Channel<Item>(capacity)
        queue = q
        writer = scope.launch(writerDispatcher) {
            val batch = mutableListOf<PebbleEvent>()
            for (first in q) {
                val flushes = mutableListOf<CompletableDeferred<Unit>>()
                var item: Item? = first
                while (item != null && batch.size < BATCH) {
                    when (item) {
                        is Item.Event -> batch += item.event
                        is Item.Flushed -> flushes += item.done
                    }
                    item = q.tryReceive().getOrNull()
                }
                if (batch.isNotEmpty()) db.transaction { batch.forEach(::log) }
                batch.clear()
                when (item) { // the one received past a full batch
                    is Item.Event -> log(item.event)

                    is Item.Flushed -> flushes += item.done

                    null -> Unit
                }
                flushes.forEach { it.complete(Unit) }
            }
        }
        return scope.launch(Dispatchers.Unconfined) {
            bus.events.collect { e -> if (q.trySend(Item.Event(e)).isFailure) log(e) }
        }
    }

    /** Waits until every event queued before this call is saved; false if that took longer than [timeout]. */
    suspend fun flush(timeout: Duration = 2.seconds): Boolean {
        val q = queue ?: return true
        val done = CompletableDeferred<Unit>()
        if (q.trySend(Item.Flushed(done)).isFailure) return writer?.let { withTimeoutOrNull(timeout) { it.join() } != null } ?: true
        return withTimeoutOrNull(timeout) { done.await() } != null
    }

    /**
     * On exit: stops queueing (later events are written at once), and waits for the writer to save
     * the rest — at most [timeout]. True if everything was saved in time.
     */
    suspend fun close(timeout: Duration = 2.seconds): Boolean {
        val q = queue ?: return true
        q.close()
        return withTimeoutOrNull(timeout) { writer?.join() } != null
    }

    private companion object {
        const val BATCH = 256
    }
}
