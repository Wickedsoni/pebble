package dev.pebble.core.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process pub/sub hub. Publishing never suspends; slow subscribers drop the oldest events. */
class EventBus {
    private val _events = MutableSharedFlow<PebbleEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PebbleEvent> = _events.asSharedFlow()

    fun publish(event: PebbleEvent) {
        _events.tryEmit(event)
    }
}
