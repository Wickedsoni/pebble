package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.db.writeTransaction
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.history.HistoryCompactor
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.db.PebbleDatabase
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A transaction that reads and then writes, while the event writer saves on another thread. SQLDelight begins
 * transactions DEFERRED; in WAL such a transaction fails at once with SQLITE_BUSY_SNAPSHOT when another connection
 * wrote after its read (`busy_timeout` does not help). [writeTransaction] takes the write lock first.
 * Seen in CI on PR #40 (`ReminderRepository.addLinked`) and in `pebble.log` ("history roll-up failed").
 */
class ConcurrentWriteTest {
    private fun fileDb(): Pair<PebbleDatabase, File> {
        val file = Files.createTempFile("pebble-busy", ".db").toFile().apply { delete() }
        listOf("", "-wal", "-shm").forEach { File(file.path + it).deleteOnExit() }
        return DatabaseFactory.create(file) to file
    }

    /**
     * Runs [transaction] with a read, then a write. Between them, another thread logs an event. Returns the error
     * of the transaction, if any. [writerCanFinishFirst]: wait for that event to be saved before the write.
     */
    private fun raceWithTheEventWriter(
        db: PebbleDatabase,
        writerCanFinishFirst: Boolean,
        transaction: (body: () -> Unit) -> Unit,
    ): Throwable? {
        val readDone = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        var writerError: Throwable? = null
        val writer = thread {
            readDone.await()
            writerError = runCatching { EventLogger(db).log(PebbleEvent.AppStarted(1)) }.exceptionOrNull()
            writerDone.countDown()
        }
        val q = db.remindersQueries
        val error = runCatching {
            transaction {
                q.linkedOneOffExists("event-1", 1_000).executeAsOne() // the shape of ReminderRepository.addLinked
                readDone.countDown()
                if (writerCanFinishFirst) writerDone.await(10, TimeUnit.SECONDS) else Thread.sleep(300)
                q.insertLinkedOneOff("Dentist", 1_000, "NORMAL", 0, "event-1", 1_000)
            }
        }.exceptionOrNull()
        readDone.countDown()
        writer.join(10_000)
        assertNull(writerError, "the event writer saves (it waits for the busy timeout if it must)")
        assertEquals(1, db.pebbleQueries.recentEvents(10).executeAsList().size)
        return error
    }

    @Test
    fun aDeferredReadThenWriteFailsWhenAnotherConnectionWroteInBetween() {
        // Why writeTransaction exists: this is what SQLDelight's own transaction does.
        val (db, _) = fileDb()
        val error = raceWithTheEventWriter(db, writerCanFinishFirst = true) { body -> db.transaction { body() } }
        assertTrue(error?.message?.contains("SQLITE_BUSY_SNAPSHOT") == true, "expected SQLITE_BUSY_SNAPSHOT, got $error")
    }

    @Test
    fun writeTransactionMakesTheOtherWriterWait() {
        for (db in listOf(fileDb().first, DatabaseFactory.inMemory())) {
            val error = raceWithTheEventWriter(db, writerCanFinishFirst = false) { body -> db.writeTransaction { body() } }
            assertNull(error, "no SQLITE_BUSY: ${error?.message}")
            assertEquals(1L, db.remindersQueries.linkedOneOffExists("event-1", 1_000).executeAsOne())
        }
    }

    @Test
    fun writeTransactionHoldsTheWriteLockBeforeItReads() {
        val (db, file) = fileDb()
        db.writeTransaction {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { s ->
                    s.execute("PRAGMA busy_timeout = 0")
                    val blocked = runCatching { s.execute("BEGIN IMMEDIATE") }.exceptionOrNull()
                    assertTrue(blocked?.message?.contains("SQLITE_BUSY") == true, "the write lock is taken at the start: $blocked")
                }
            }
        }
        // It changes nothing.
        assertNull(db.pebbleQueries.selectSetting("anything").executeAsOneOrNull())
    }

    /** The two real read-then-write transactions, many times, while the event writer saves without a pause. */
    @Test
    fun addLinkedAndTheRollUpDoNotFailWhileTheEventWriterSaves() {
        val day = 24 * 60 * 60_000L
        val (db, _) = fileDb()
        val logger = EventLogger(db)
        val reminders = ReminderRepository(db)
        val compactor = HistoryCompactor(db, dayOf = { it / day })
        val stop = AtomicBoolean(false)
        var logged = 0
        val writer = thread {
            while (!stop.get()) {
                logger.log(PebbleEvent.ActiveHour(9, logged * 60_000L))
                logged++
            }
        }
        try {
            repeat(200) { i ->
                reminders.addLinked("Dentist", 1_000, "event-$i", 1_000, 0)
                compactor.rollUpBefore((i / 20 + 1) * day)
            }
        } finally {
            stop.set(true)
            writer.join(10_000)
        }
        assertEquals(200, (0 until 200).count { db.remindersQueries.linkedOneOffExists("event-$it", 1_000).executeAsOne() == 1L })
        assertEquals(logged, db.pebbleQueries.recentEvents(Long.MAX_VALUE).executeAsList().size, "no row lost or deleted")
    }
}
