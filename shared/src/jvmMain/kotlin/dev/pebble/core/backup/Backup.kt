package dev.pebble.core.backup

import dev.pebble.db.PebbleDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager

/**
 * Encrypted backup and restore of the whole database (WP E4, ADR 0015).
 *
 * - **Export:** SQLite `VACUUM INTO` a temp file next to the database (a consistent copy, WAL included; trap 11),
 *   then [BackupFile.encrypt] it. The plain temp copy is always deleted.
 * - **Restore:** [stageRestore] decrypts and checks the file (`integrity_check`, schema not newer than this app),
 *   then keeps it as `pebble.db.restore`. [applyStaged] swaps it in at the next start, before Pebble opens the
 *   database; the old files are kept as `pebble.db.before-restore`. Migrations then upgrade an older backup.
 * - This device keeps its own `device.id`: a backup restored on a new computer does not make two devices with one id.
 */
object Backup {
    const val DB = "pebble.db"
    private const val STAGED = "$DB.restore"
    private const val BEFORE = "$DB.before-restore"
    private const val RESTORING = ".restoring-"
    private val SIDE = listOf("", "-wal", "-shm")

    /** What a backup holds, for the message after an export or a staged restore. */
    data class Summary(val notes: Long, val reminders: Long, val events: Long, val schemaVersion: Int) {
        override fun toString() = "$notes notes, $reminders reminders, $events calendar events"
    }

    /** Writes an encrypted copy of [db] to [out]. Call `eventLog.flush()` first, so queued events are in it. */
    fun export(db: File, out: Path, passphrase: CharArray, iterations: Int = BackupFile.ITERATIONS): Summary {
        val plain = File(db.parentFile, "$DB.export-${System.nanoTime()}.tmp")
        val partial = out.resolveSibling("${out.fileName}.partial")
        try {
            connect(db).use { c -> c.createStatement().use { it.execute("VACUUM INTO '${plain.absolutePath.replace("'", "''")}'") } }
            val summary = summary(plain)
            plain.inputStream().buffered().use { input ->
                Files.newOutputStream(partial).buffered().use { output -> BackupFile.encrypt(input, output, passphrase, iterations) }
            }
            Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return summary
        } finally {
            plain.delete()
            Files.deleteIfExists(partial)
        }
    }

    /**
     * Decrypts and checks [backup], then keeps it in [dataDir] to restore at the next start.
     * Throws [BackupException] when the file cannot be used; nothing is staged then.
     */
    fun stageRestore(backup: Path, dataDir: File, passphrase: CharArray): Summary {
        val tmp = File(dataDir, "$STAGED.tmp")
        try {
            Files.newInputStream(backup).buffered().use { input ->
                tmp.outputStream().buffered().use { output -> BackupFile.decrypt(input, output, passphrase) }
            }
            val summary = runCatching { check(tmp) }.getOrElse { e ->
                throw e as? BackupException ?: BackupException("The backup does not hold a Pebble database.")
            }
            Files.move(tmp.toPath(), File(dataDir, STAGED).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return summary
        } finally {
            SIDE.forEach { File(tmp.path + it).delete() }
        }
    }

    fun restorePending(dataDir: File): Boolean = File(dataDir, STAGED).exists()

    fun cancelRestore(dataDir: File) {
        File(dataDir, STAGED).delete()
    }

    /**
     * At start, before the database is opened: swaps in a staged restore. Returns a line for the log, or null when
     * nothing was staged. A staged file that fails its check again stays out (it is renamed `.failed`).
     */
    fun applyStaged(dataDir: File): String? = applyStaged(dataDir, ::moveFile)

    /**
     * The swap never deletes before it succeeds: the old files move aside to `pebble.db.restoring-<time>`, the staged
     * file moves in, and only then do the old files become `pebble.db.before-restore`. When a step fails, every move
     * is undone (in reverse order), the staged file stays for the next start, and a [BackupException] is thrown.
     * [move] is a test seam.
     */
    internal fun applyStaged(dataDir: File, move: (Path, Path) -> Unit): String? {
        recoverInterrupted(dataDir)
        val staged = File(dataDir, STAGED)
        if (!staged.exists()) return null
        runCatching { check(staged) }.onFailure {
            staged.renameTo(File(dataDir, "$STAGED.failed"))
            return "restore refused at start: ${it.message}"
        }
        val db = File(dataDir, DB)
        // A device id this computer cannot read must not let the backup's id through: two devices would share one.
        val deviceId = if (db.exists()) {
            runCatching { connect(db).use(::deviceIdOf) }.getOrNull() ?: dev.pebble.core.settings.DeviceIdentity.newId()
        } else {
            null
        }
        // The ids go into the staged file, which is closed and checked, so the swap itself is renames only.
        try {
            writeIds(staged, deviceId)
        } catch (e: Exception) {
            throw BackupException("The restore failed; the old database stays (${e.message}).")
        }
        val aside = "$DB$RESTORING${System.currentTimeMillis()}"
        val moved = ArrayDeque<Pair<Path, Path>>() // (from, to), in the order done
        fun step(from: File, to: File) {
            move(from.toPath(), to.toPath())
            moved.addLast(from.toPath() to to.toPath())
        }
        try {
            SIDE.forEach { suffix ->
                val from = File(db.path + suffix)
                if (from.exists()) step(from, File(dataDir, aside + suffix))
            }
            step(staged, db)
        } catch (e: Exception) {
            val failure = BackupException("The restore failed; the old database stays (${e.message}).")
            while (moved.isNotEmpty()) {
                val (from, to) = moved.removeLast()
                // Never write over a file that is there: that could destroy the one copy of the restore or of the old data.
                if (Files.exists(from)) {
                    failure.addSuppressed(IllegalStateException("cannot undo, $from exists"))
                } else {
                    runCatching { move(to, from) }.onFailure(failure::addSuppressed)
                }
            }
            throw failure
        }
        // Only now replace the older before-restore files: all three, so a stale -wal never meets a new database.
        SIDE.forEach { File(dataDir, BEFORE + it).delete() }
        SIDE.forEach { suffix ->
            val old = File(dataDir, aside + suffix)
            if (old.exists()) runCatching { move(old.toPath(), File(dataDir, BEFORE + suffix).toPath()) }
        }
        // Older leftovers of other swaps: a later manual reset of `pebble.db` must not be undone by recoverInterrupted.
        dataDir.listFiles { f -> f.name.startsWith("$DB$RESTORING") && !f.name.startsWith(aside) }?.forEach { it.delete() }
        return "restored a backup; the old database is $BEFORE"
    }

    /**
     * Puts the old database back when a swap was cut short (a crash or a failed undo) and `pebble.db` is missing.
     * Returns true if it moved files back. Call it before Pebble opens the database, so a new empty one is never made.
     */
    fun recoverInterrupted(dataDir: File): Boolean {
        if (File(dataDir, DB).exists()) return false
        val newest = dataDir.list().orEmpty()
            .filter { it.startsWith("$DB$RESTORING") && !it.endsWith("-wal") && !it.endsWith("-shm") }
            .maxOrNull() ?: return false
        // The main file moves last: if a move fails in between, `pebble.db` is still missing and this runs again.
        SIDE.drop(1).forEach { suffix ->
            val from = File(dataDir, newest + suffix)
            if (from.exists()) moveFile(from.toPath(), File(dataDir, DB + suffix).toPath())
        }
        moveFile(File(dataDir, newest).toPath(), File(dataDir, DB).toPath())
        return true
    }

    private fun moveFile(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeIds(db: File, deviceId: String?) = connect(db).use { c ->
        if (deviceId != null) {
            c.prepareStatement("INSERT OR REPLACE INTO setting(key, value) VALUES ('device.id', ?)").use {
                it.setString(1, deviceId)
                it.executeUpdate()
            }
        }
        // A new journal epoch (WP E3b, spec 8): the restored journal's seq can be smaller than a peer's cursor,
        // so peers read it again from the start (the merge is idempotent).
        c.prepareStatement("INSERT OR REPLACE INTO setting(key, value) VALUES ('sync.journalEpoch', ?)").use {
            it.setString(1, dev.pebble.core.settings.DeviceIdentity.newId())
            it.executeUpdate()
        }
    }

    /** Checks that [file] is a whole Pebble database this app can open. */
    private fun check(file: File): Summary = connect(file).use { c ->
        val integrity = c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) } }
        if (integrity != "ok") throw BackupException("The database in the backup is damaged ($integrity).")
        val version = c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        if (version < 1) throw BackupException("The backup does not hold a Pebble database.")
        if (version > PebbleDatabase.Schema.version) throw BackupException("This backup was made by a newer Pebble. Update Pebble first.")
        if (!hasTable(c, "setting")) throw BackupException("The backup does not hold a Pebble database.")
        summaryOf(c, version)
    }

    private fun summary(file: File): Summary = connect(file).use { c ->
        summaryOf(c, c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } })
    }

    private fun summaryOf(c: Connection, version: Int): Summary {
        fun count(table: String, where: String) = if (!hasTable(c, table)) {
            0L
        } else {
            val live = if (hasColumn(c, table, "deleted_at")) "deleted_at IS NULL" else "1"
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM $table WHERE $live AND $where").use { it.next(); it.getLong(1) }
            }
        }
        return Summary(count("note", "archived = 0"), count("one_off_reminder", "done_at IS NULL"), count("calendar_event", "1"), version)
    }

    private fun hasTable(c: Connection, name: String) = c.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
    ).use {
        it.setString(1, name)
        it.executeQuery().use { r -> r.next() }
    }

    private fun hasColumn(c: Connection, table: String, column: String) =
        c.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info($table)").use { r ->
                generateSequence { if (r.next()) r.getString("name") else null }.any {
                    it ==
                        column
                }
            }
        }

    private fun deviceIdOf(c: Connection): String? = if (!hasTable(c, "setting")) {
        null
    } else {
        c.createStatement().use { s ->
            s.executeQuery("SELECT value FROM setting WHERE key = 'device.id'").use { if (it.next()) it.getString(1) else null }
        }
    }

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
}
