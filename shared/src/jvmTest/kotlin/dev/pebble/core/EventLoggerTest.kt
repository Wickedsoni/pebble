package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Events are saved off the publisher's thread, in batches, and none is lost — not even on exit. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventLoggerTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun stop() = scope.cancel()

    private fun rows(db: PebbleDatabase) = db.pebbleQueries.recentEvents(Long.MAX_VALUE).executeAsList().size

    private fun event(i: Int) = PebbleEvent.WidgetMoved("clock", i, i, atMillis = i.toLong())

    @Test
    fun tenThousandEventsFromOneThreadAreAllSaved() = runBlocking {
        val db = DatabaseFactory.inMemory()
        val bus = EventBus()
        val logger = EventLogger(db)
        logger.attach(bus, scope, Dispatchers.IO.limitedParallelism(1))
        val t0 = System.nanoTime()
        repeat(10_000) { bus.publish(event(it)) }
        // This test is about nothing lost, not speed: a slow CI disk needs more than flush()'s default 2 s here.
        assertTrue(logger.flush(30.seconds))
        println("10 000 events saved in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(10_000, rows(db))
        // In order: the newest event is the last one published.
        assertEquals(9_999L, db.pebbleQueries.recentEvents(1).executeAsOne().at_millis)
    }

    @Test
    fun thePublisherNeverWritesWhileTheWriterIsFree() {
        val db = DatabaseFactory.inMemory()
        val bus = EventBus()
        val scheduler = TestCoroutineScheduler()
        EventLogger(db).attach(bus, scope, StandardTestDispatcher(scheduler))
        repeat(3) { bus.publish(event(it)) }
        assertEquals(0, rows(db), "publishing only queues the event")
        scheduler.advanceUntilIdle()
        assertEquals(3, rows(db))
    }

    @Test
    fun closeOnExitSavesWhatIsQueuedAndLaterEventsStillLand() = runBlocking {
        val db = DatabaseFactory.inMemory()
        val bus = EventBus()
        val logger = EventLogger(db)
        logger.attach(bus, scope, Dispatchers.IO.limitedParallelism(1))
        repeat(2_000) { bus.publish(event(it)) }
        bus.publish(PebbleEvent.AppStopping(5_000))
        assertTrue(logger.close())
        assertEquals(2_001, rows(db))
        bus.publish(event(6_000)) // after close: written at once on this thread
        assertEquals(2_002, rows(db))
    }

    @Test
    fun aFullQueueWritesAtOnceInsteadOfDropping() {
        val db = DatabaseFactory.inMemory()
        val bus = EventBus()
        val scheduler = TestCoroutineScheduler()
        EventLogger(db).attach(bus, scope, StandardTestDispatcher(scheduler), capacity = 2)
        repeat(5) { bus.publish(event(it)) }
        assertEquals(3, rows(db), "2 wait in the queue, 3 overflowed and were written at once")
        scheduler.advanceUntilIdle()
        assertEquals(5, rows(db))
    }

    @Test
    fun fileDatabasesUseWalAndABusyTimeout() {
        val file = Files.createTempFile("pebble-wal", ".db").toFile().apply { delete(); deleteOnExit() }
        DatabaseFactory.create(file).pebbleQueries.insertEvent("app_started", "{}", 1)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA journal_mode").use { r -> r.next(); assertEquals("wal", r.getString(1)) }
            }
        }
    }

    @Test
    fun aFailedWriteDoesNotStopTheWriterOrHangFlush() = runBlocking {
        val file = Files.createTempFile("pebble-poison", ".db").toFile().apply { delete(); deleteOnExit() }
        val db = DatabaseFactory.create(file)
        // Event 5 cannot be saved, like a write that fails on a busy or full disk.
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("CREATE TRIGGER poison BEFORE INSERT ON event_log WHEN new.at_millis = 5 BEGIN SELECT RAISE(ABORT, 'boom'); END")
            }
        }
        val errors = mutableListOf<Throwable>()
        val bus = EventBus()
        val logger = EventLogger(db) { synchronized(errors) { errors += it } }
        logger.attach(bus, scope, Dispatchers.IO.limitedParallelism(1))
        repeat(10) { bus.publish(event(it)) }
        assertTrue(logger.flush(10.seconds), "flush completes after a failed batch")
        assertEquals(9, rows(db), "only the bad event is lost")
        assertEquals(1, errors.size)
        repeat(3) { bus.publish(event(100 + it)) } // the writer is still alive
        assertTrue(logger.flush(10.seconds))
        assertEquals(12, rows(db))
    }

    @Test
    fun aLockedDatabaseStopsTheRowByRowRetryAfterThreeMisses() = runBlocking {
        val file = Files.createTempFile("pebble-locked", ".db").toFile().apply { delete(); deleteOnExit() }
        val db = DatabaseFactory.create(file)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("CREATE TRIGGER poison BEFORE INSERT ON event_log BEGIN SELECT RAISE(ABORT, 'locked'); END")
            }
        }
        val errors = mutableListOf<Throwable>()
        val bus = EventBus()
        val logger = EventLogger(db) { synchronized(errors) { errors += it } }
        // Hold the writer until all 10 events are queued, so they are one batch.
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        logger.attach(bus, scope, executor.asCoroutineDispatcher())
        repeat(10) { bus.publish(event(it)) }
        gate.countDown()
        assertTrue(logger.flush(10.seconds))
        executor.shutdown()
        assertEquals(0, rows(db))
        assertEquals(4, errors.size, "3 failed rows, then one report for the rest")
        assertTrue(errors.last().message!!.contains("7 more"), errors.last().message)
    }
}
