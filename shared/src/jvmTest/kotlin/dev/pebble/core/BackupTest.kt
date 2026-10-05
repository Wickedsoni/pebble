package dev.pebble.core

import dev.pebble.core.backup.Backup
import dev.pebble.core.backup.BackupException
import dev.pebble.core.backup.BackupFile
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.wellness.NoteRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WP E4: the encrypted backup file, and export → restore of a real database. */
class BackupTest {
    private val pass = "correct horse battery".toCharArray()

    /** Few iterations, so the tests are fast; the file stores the count. */
    private fun encrypt(plain: ByteArray, passphrase: CharArray = pass.copyOf()): ByteArray =
        ByteArrayOutputStream().also { BackupFile.encrypt(ByteArrayInputStream(plain), it, passphrase, iterations = 1_000) }.toByteArray()

    private fun decrypt(file: ByteArray, passphrase: CharArray = pass.copyOf()): ByteArray =
        ByteArrayOutputStream().also { BackupFile.decrypt(ByteArrayInputStream(file), it, passphrase) }.toByteArray()

    @Test
    fun roundTripsAnySize() {
        val chunk = BackupFile.CHUNK
        for (size in listOf(0, 1, 1000, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 5)) {
            val plain = Random(size).nextBytes(size)
            val file = encrypt(plain)
            assertContentEquals(plain, decrypt(file), "size $size")
            assertEquals(36 + size + 16 * maxOf(1, (size + chunk - 1) / chunk), file.size, "header + data + one tag per chunk, size $size")
        }
    }

    @Test
    fun theSameDataGivesADifferentFileEachTime() {
        val plain = "same".toByteArray()
        assertFalse(encrypt(plain).contentEquals(encrypt(plain)), "random salt and nonce")
    }

    @Test
    fun aWrongPassphraseIsRefused() {
        val file = encrypt("secret notes".toByteArray())
        val e = assertFailsWith<BackupException> { decrypt(file, "wrong horse battery".toCharArray()) }
        assertTrue(e.message!!.startsWith("Wrong passphrase"))
    }

    @Test
    fun aCutFileIsRefusedAlsoAtAChunkBoundary() {
        val chunk = BackupFile.CHUNK
        val file = encrypt(Random(1).nextBytes(2 * chunk + 100))
        val boundary = 36 + 2 * (chunk + 16) // without the last chunk
        for (cut in listOf(boundary, boundary - 1, 36 + chunk + 16, 36 + 10, 36, 20, file.size - 1)) {
            assertFailsWith<BackupException>("cut at $cut") { decrypt(file.copyOf(cut)) }
        }
    }

    @Test
    fun anyChangedByteIsRefused() {
        val file = encrypt(Random(2).nextBytes(5_000))
        for (i in listOf(8, 9, 12, 20, 33, 40, 2_000, file.size - 1)) { // version, iterations, salt, nonce, data, tag
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<BackupException>("byte $i") { decrypt(bad) }
        }
        assertFailsWith<BackupException>("appended bytes") { decrypt(file + ByteArray(20)) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertEquals(
            "This is not a Pebble backup file.",
            assertFailsWith<BackupException> {
                decrypt("SQLite format 3".toByteArray())
            }.message,
        )
        assertFailsWith<BackupException> { decrypt(ByteArray(0)) }
    }

    @Test
    fun theRealKeyDerivationTakesAboutASecondOrLess() {
        val t = System.nanoTime()
        ByteArrayOutputStream().also { BackupFile.encrypt(ByteArrayInputStream(ByteArray(10)), it, pass.copyOf(), random = SecureRandom()) }
        val ms = (System.nanoTime() - t) / 1_000_000
        println("PBKDF2-HMAC-SHA256, ${BackupFile.ITERATIONS} iterations: $ms ms")
        assertTrue(ms < 5_000, "$ms ms")
    }

    private fun dataDir(): File = Files.createTempDirectory("pebble-backup").toFile().apply { deleteOnExit() }

    private fun setDevice(db: File, id: String) = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
        c.createStatement().use { it.execute("INSERT OR REPLACE INTO setting(key, value) VALUES ('device.id', '$id')") }
    }

    private fun deviceOf(db: File) = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT value FROM setting WHERE key = 'device.id'").use { it.next(); it.getString(1) }
        }
    }

    @Test
    fun exportThenRestoreOnAnotherComputerKeepsItsDeviceId() {
        // Computer A: notes and an event, some of it still in the WAL file.
        val a = dataDir()
        val dbA = File(a, Backup.DB)
        val db = DatabaseFactory.create(dbA)
        SettingsRepository(db).set(SettingsRepository.Keys.DEVICE_ID, "devicea")
        NoteRepository(db).add("buy milk", 1)
        NoteRepository(db).add("call plumber", 2)
        CalendarRepository(db).save(CalendarEvent("e", "Dentist", 10, 20, "Asia/Kolkata"), at = 1)
        val out = File(a, "my.pebblebackup").toPath()
        val exported = Backup.export(dbA, out, pass.copyOf(), iterations = 1_000)
        assertEquals("2 notes, 0 reminders, 1 calendar events", exported.toString())
        val left = a.list()!!.toList()
        assertTrue(left.none { it.endsWith(".tmp") || it.endsWith(".partial") }, "no plain or partial copy left behind: $left")
        assertFalse(String(Files.readAllBytes(out), Charsets.ISO_8859_1).contains("buy milk"), "encrypted")

        // Computer B: its own database and device id.
        val b = dataDir()
        val dbB = File(b, Backup.DB)
        DatabaseFactory.create(dbB).let { NoteRepository(it).add("only on B", 1) }
        setDevice(dbB, "deviceb")
        assertFailsWith<BackupException> { Backup.stageRestore(out, b, "wrong passphrase".toCharArray()) }
        assertFalse(Backup.restorePending(b), "nothing staged after a failure")
        assertEquals(exported, Backup.stageRestore(out, b, pass.copyOf()))
        assertTrue(Backup.restorePending(b))

        // Next start on B.
        assertTrue(Backup.applyStaged(b)!!.startsWith("restored"))
        assertFalse(Backup.restorePending(b))
        val restored = DatabaseFactory.create(dbB)
        assertEquals(listOf("call plumber", "buy milk"), NoteRepository(restored).recent(10).map { it.text })
        assertEquals(listOf("Dentist"), CalendarRepository(restored).live().map { it.title })
        assertEquals("deviceb", deviceOf(dbB), "B keeps its own device id")
        assertTrue(File(b, "${Backup.DB}.before-restore").exists(), "B's old data is kept")
        assertEquals(null, Backup.applyStaged(b), "nothing more to apply")
    }

    @Test
    fun aBackupFromANewerPebbleOrNotADatabaseIsNotStaged() {
        val dir = dataDir()
        val newer = File(dir, "newer.db")
        DriverManager.getConnection("jdbc:sqlite:${newer.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                it.execute("PRAGMA user_version = 999")
            }
        }
        val newerBackup = File(dir, "newer.pebblebackup").apply { writeBytes(encrypt(newer.readBytes())) }.toPath()
        assertTrue(
            assertFailsWith<BackupException> {
                Backup.stageRestore(newerBackup, dir, pass.copyOf())
            }.message!!.contains("newer Pebble"),
        )
        val junk = File(dir, "junk.pebblebackup").apply { writeBytes(encrypt("not a database at all".toByteArray())) }.toPath()
        assertFailsWith<BackupException> { Backup.stageRestore(junk, dir, pass.copyOf()) }
        assertFalse(Backup.restorePending(dir))
        assertEquals(listOf("junk.pebblebackup", "newer.db", "newer.pebblebackup"), dir.list()!!.sorted(), "no decrypted copy left behind")
    }

    @Test
    fun aStagedRestoreCanBeCancelled() {
        val dir = dataDir()
        val dbFile = File(dir, Backup.DB)
        DatabaseFactory.create(dbFile).let { NoteRepository(it).add("keep me", 1) }
        val out = File(dir, "b.pebblebackup").toPath()
        Backup.export(dbFile, out, pass.copyOf(), iterations = 1_000)
        Backup.stageRestore(out, dir, pass.copyOf())
        Backup.cancelRestore(dir)
        assertFalse(Backup.restorePending(dir))
        assertEquals(null, Backup.applyStaged(dir))
    }
}
