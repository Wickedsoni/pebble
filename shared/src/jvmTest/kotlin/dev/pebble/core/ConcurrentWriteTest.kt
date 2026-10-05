package dev.pebble.core

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.history.HistoryCompactor
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.db.PebbleDatabase
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A transaction that reads and then writes, while the event writer saves on another thread (ADR 0017). In WAL a
 * deferred transaction fails at once with SQLITE_BUSY_SNAPSHOT when another connection wrote after its read, and
 * `busy_timeout` does not help. `PebbleSqliteDriver` begins every transaction IMMEDIATE, so the other writer waits.
 * Seen in CI on PR #40 (`ReminderRepository.addLinked`) and in `pebble.log` ("history roll-up failed").
 */
class ConcurrentWriteTest {
    private fun tempFile(): File = Files.createTempFile("pebble-busy", ".db").toFile().apply {
        delete()
        listOf("", "-wal", "-shm").forEach { File(path + it).deleteOnExit() }
    }

    /**
     * Runs a plain `db.transaction` that reads, then writes. Between them, another thread logs an event. Returns the
     * error of the transaction, if any. [writerCanFinishFirst]: wait for that event to be saved before the write.
     */
    private fun raceWithTheEventWriter(db: PebbleDatabase, writerCanFinishFirst: Boolean): Throwable? {
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
            db.transaction {
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
    fun sqlDelightsOwnDriverFailsWhenAnotherConnectionWroteInBetween() {
        // Why PebbleSqliteDriver exists. If a SQLDelight update makes this pass, look at ADR 0017 again.
        val props = Properties().apply {
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", "5000")
        }
        val db = PebbleDatabase(JdbcSqliteDriver("jdbc:sqlite:${tempFile().absolutePath}", props, PebbleDatabase.Schema))
        val error = raceWithTheEventWriter(db, writerCanFinishFirst = true)
        assertTrue(error?.message?.contains("SQLITE_BUSY_SNAPSHOT") == true, "expected SQLITE_BUSY_SNAPSHOT, got $error")
    }

    @Test
    fun theCopiedDriverMatchesThePinnedSqlDelight() {
        // PebbleSqliteDriver copies SQLDelight 2.4.0's JdbcSqliteDriver (ADR 0017). An update fails here on purpose:
        // compare the copy with the new JdbcSqliteDriver.kt and JdbcSqliteSchema.kt first, then change this version.
        val jar = JdbcSqliteDriver::class.java.protectionDomain.codeSource.location.path
        assertTrue("sqlite-driver-2.4.0" in jar, "SQLDelight changed ($jar): check PebbleSqliteDriver against it (ADR 0017)")
    }

    @Test
    fun everyTransactionMakesTheOtherWriterWait() {
        for (db in listOf(DatabaseFactory.create(tempFile()), DatabaseFactory.inMemory())) {
            val error = raceWithTheEventWriter(db, writerCanFinishFirst = false)
            assertNull(error, "no SQLITE_BUSY: ${error?.message}")
            assertEquals(1L, db.remindersQueries.linkedOneOffExists("event-1", 1_000).executeAsOne())
        }
    }

    @Test
    fun aTransactionHoldsTheWriteLockFromItsStart() {
        val file = tempFile()
        val db = DatabaseFactory.create(file)
        db.transaction {
            db.pebbleQueries.recentEvents(1).executeAsList() // only a read so far
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().use { s ->
                    s.execute("PRAGMA busy_timeout = 0")
                    val blocked = runCatching { s.execute("BEGIN IMMEDIATE") }.exceptionOrNull()
                    assertTrue(blocked?.message?.contains("SQLITE_BUSY") == true, "the write lock is taken at BEGIN: $blocked")
                }
            }
        }
    }

    /** The two real read-then-write transactions, many times, while the event writer saves without a pause. */
    @Test
    fun addLinkedAndTheRollUpDoNotFailWhileTheEventWriterSaves() {
        val day = 24 * 60 * 60_000L
        val db = DatabaseFactory.create(tempFile())
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

    @Test
    fun liveQueriesStillHearAboutChanges() {
        // The pages' Flows (asFlow) depend on the driver's listeners, which PebbleSqliteDriver implements again.
        val db = DatabaseFactory.inMemory()
        val heard = AtomicInteger()
        val query = db.remindersQueries.pendingOneOffs()
        val listener = Query.Listener { heard.incrementAndGet() }
        query.addListener(listener)
        ReminderRepository(db).addOneOff("Call mom", 1_000)
        db.transaction { ReminderRepository(db).addOneOff("Tea", 2_000) }
        assertEquals(2, heard.get(), "one change outside and one inside a transaction")
        query.removeListener(listener)
        ReminderRepository(db).addOneOff("Walk", 3_000)
        assertEquals(2, heard.get())
    }
}
