package dev.pebble.core

import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class EventBusTest {
    @Test
    fun subscriberReceivesPublishedEvent() = runTest {
        val bus = EventBus()
        val event = PebbleEvent.WidgetMoved("clock", 10, 20, atMillis = 1)
        var received: PebbleEvent? = null
        val job = launch { received = bus.events.first() }
        yield()
        bus.publish(event)
        job.join()
        assertEquals(event, received)
    }
}
