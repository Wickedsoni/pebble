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
import java.nio.file.Path
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

    /** A data folder with a real old database (notes, device id) plus fake side files, and a staged restore. */
    private fun withStaged(): File {
        val dir = dataDir()
        val dbFile = File(dir, Backup.DB)
        DatabaseFactory.create(dbFile).let { NoteRepository(it).add("old note", 1) }
        setDevice(dbFile, "olddevice")
        val other = File(dataDir(), Backup.DB)
        DatabaseFactory.create(other).let { NoteRepository(it).add("backup note", 1) }
        setDevice(other, "backupdevice")
        val out = File(dir, "b.pebblebackup").toPath()
        Backup.export(other, out, pass.copyOf(), iterations = 1_000)
        Backup.stageRestore(out, dir, pass.copyOf())
        return dir
    }

    /**
     * A mover that fails when [failOn] says so. SQLite removes fake side files when it opens the old database, so the
     * first call makes them (as a database in use has them), just before the moves reach them.
     */
    private fun failingMover(dir: File, failOn: (Int, Path) -> Boolean): (Path, Path) -> Unit {
        var calls = 0
        return { from, to ->
            if (++calls == 1) {
                File(dir, "${Backup.DB}-wal").writeText("wal")
                File(dir, "${Backup.DB}-shm").writeText("shm")
            }
            if (failOn(calls, from)) error("locked") else Files.move(from, to)
        }
    }

    private fun assertOldDataIntact(dir: File) {
        assertEquals("wal", File(dir, "${Backup.DB}-wal").readText())
        assertEquals("shm", File(dir, "${Backup.DB}-shm").readText())
        assertTrue(Backup.restorePending(dir), "the staged file is kept for the next start")
        assertTrue(
            dir.list()!!.none {
                it.contains("restoring") || it.contains("before-restore")
            },
            "nothing left aside: ${dir.list()!!.toList()}",
        )
    }

    @Test
    fun aFailedMoveOfASideFileLeavesTheOldDatabaseWhereItWas() {
        val dir = withStaged()
        val e = assertFailsWith<BackupException> {
            Backup.applyStaged(dir, failingMover(dir) { call, _ -> call == 3 })
        }
        assertTrue(e.message!!.contains("old database stays"))
        assertOldDataIntact(dir)
        assertEquals("olddevice", deviceOf(File(dir, Backup.DB)))
    }

    @Test
    fun aFailedMoveOfTheRestoredFileLeavesTheOldDatabaseWhereItWas() {
        val dir = withStaged()
        val e = assertFailsWith<BackupException> {
            Backup.applyStaged(dir, failingMover(dir) { _, from -> from.fileName.toString() == "${Backup.DB}.restore" })
        }
        assertTrue(e.message!!.contains("old database stays"))
        assertOldDataIntact(dir)
        assertEquals("olddevice", deviceOf(File(dir, Backup.DB)))
    }

    @Test
    fun aSwapCutShortIsUndoneAtTheNextStart() {
        val dir = dataDir()
        // As left by a crash after the old files moved aside and before the restore moved in.
        File(dir, "${Backup.DB}.restoring-100").writeText("db")
        File(dir, "${Backup.DB}.restoring-100-wal").writeText("wal")
        assertTrue(Backup.recoverInterrupted(dir))
        assertEquals("db", File(dir, Backup.DB).readText())
        assertEquals("wal", File(dir, "${Backup.DB}-wal").readText())
        assertFalse(Backup.recoverInterrupted(dir), "nothing more to do")
    }

    @Test
    fun anUnreadableOldDeviceIdDoesNotLetTheBackupsIdThrough() {
        val dir = withStaged()
        File(dir, Backup.DB).writeText("this is not a database") // the old file cannot be read
        assertTrue(Backup.applyStaged(dir)!!.startsWith("restored"))
        val id = deviceOf(File(dir, Backup.DB))
        assertTrue(id != "backupdevice" && id.isNotBlank(), "a new id, not the backup's: $id")
    }

    private fun settingOf(db: File, key: String) = DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT value FROM setting WHERE key = '$key'").use { if (it.next()) it.getString(1) else null }
        }
    }

    @Test
    fun aStaleBeforeRestoreSideFileDoesNotSurviveANewRestore() {
        val dir = withStaged()
        File(dir, "${Backup.DB}.before-restore-wal").writeText("stale wal from an earlier restore")
        File(dir, "${Backup.DB}.before-restore-shm").writeText("stale shm")
        assertTrue(Backup.applyStaged(dir)!!.startsWith("restored"))
        assertTrue(File(dir, "${Backup.DB}.before-restore").exists())
        assertFalse(File(dir, "${Backup.DB}.before-restore-wal").exists())
        assertFalse(File(dir, "${Backup.DB}.before-restore-shm").exists())
    }

    @Test
    fun theIdsAreInTheStagedFileBeforeAnyMove() {
        val dir = withStaged()
        assertFailsWith<BackupException> {
            Backup.applyStaged(dir, failingMover(dir) { _, from -> from.fileName.toString() == "${Backup.DB}.restore" })
        }
        val staged = File(dir, "${Backup.DB}.restore")
        assertEquals("olddevice", settingOf(staged, "device.id"), "so a crash right after the move cannot keep the backup's id")
        assertTrue(!settingOf(staged, "sync.journalEpoch").isNullOrBlank())
    }

    @Test
    fun undoFailuresAreReportedAndNothingIsOverwritten() {
        val dir = withStaged()
        var calls = 0
        val e = assertFailsWith<BackupException> {
            Backup.applyStaged(dir) { from, to ->
                if (++calls == 1) {
                    File(dir, "${Backup.DB}-wal").writeText("wal")
                    File(dir, "${Backup.DB}-shm").writeText("shm")
                }
                if (calls >= 3) error("locked") else Files.move(from, to)
            }
        }
        assertTrue(e.suppressed.isNotEmpty(), "undo failures are kept on the exception")
        assertTrue(Backup.restorePending(dir), "the staged copy is not lost")
    }

    @Test
    fun olderRestoringSetsAreRemovedAfterASuccessfulSwap() {
        val dir = withStaged()
        File(dir, "${Backup.DB}.restoring-1").writeText("old leftover")
        File(dir, "${Backup.DB}.restoring-1-wal").writeText("old leftover")
        assertTrue(Backup.applyStaged(dir)!!.startsWith("restored"))
        assertTrue(dir.list()!!.none { it.contains("restoring") }, dir.list()!!.toList().toString())
    }
}
