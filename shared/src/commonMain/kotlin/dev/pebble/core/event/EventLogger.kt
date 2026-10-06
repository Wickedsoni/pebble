package dev.pebble.core.event

import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.CancellationException
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
 * A failed write (a busy or full disk) never stops the writer: the batch is tried again, then row by row, and each
 * event that still fails goes to [onError].
 */
class EventLogger(private val db: PebbleDatabase, private val onError: (Throwable) -> Unit = {}) {
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
                try {
                    var item: Item? = first
                    while (item != null && batch.size < BATCH) {
                        when (item) {
                            is Item.Event -> batch += item.event
                            is Item.Flushed -> flushes += item.done
                        }
                        item = q.tryReceive().getOrNull()
                    }
                    if (batch.isNotEmpty()) writeBatch(batch)
                    when (item) { // the one received past a full batch
                        is Item.Event -> writeBatch(listOf(item.event))

                        is Item.Flushed -> flushes += item.done

                        null -> Unit
                    }
                } finally {
                    batch.clear()
                    flushes.forEach { it.complete(Unit) }
                }
            }
        }
        return scope.launch(Dispatchers.Unconfined) {
            bus.events.collect { e -> if (q.trySend(Item.Event(e)).isFailure) writeNow(e) }
        }
    }

    /** Saves [events] in one transaction; on a failure tries once more, then row by row, so one bad event costs only itself. */
    private fun writeBatch(events: List<PebbleEvent>) {
        try {
            db.transaction { events.forEach(::log) }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Often a busy database: try the whole batch once more before splitting it.
        }
        try {
            db.transaction { events.forEach(::log) }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to row by row.
        }
        // A locked database would fail every row: after a few misses in a row, report the rest and give up on this batch.
        var misses = 0
        for ((i, event) in events.withIndex()) {
            if (misses >= MAX_MISSES) {
                onError(IllegalStateException("event log: ${events.size - i} more events of the batch were not saved"))
                break
            }
            try {
                log(event)
                misses = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                misses++
                onError(e)
            }
        }
    }

    /** The direct path (queue full or closed, on the publisher's thread): one plain write, never inside a transaction. */
    private fun writeNow(event: PebbleEvent) {
        try {
            log(event)
        } catch (e: Exception) {
            onError(e)
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
        const val MAX_MISSES = 3
    }
}
