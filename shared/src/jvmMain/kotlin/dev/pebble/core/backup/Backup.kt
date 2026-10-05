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
    fun applyStaged(dataDir: File): String? {
        val staged = File(dataDir, STAGED)
        if (!staged.exists()) return null
        runCatching { check(staged) }.onFailure {
            staged.renameTo(File(dataDir, "$STAGED.failed"))
            return "restore refused at start: ${it.message}"
        }
        val db = File(dataDir, DB)
        val deviceId = if (db.exists()) runCatching { connect(db).use(::deviceIdOf) }.getOrNull() else null
        SIDE.forEach { suffix ->
            val from = File(db.path + suffix)
            val to = File(dataDir, BEFORE + suffix)
            to.delete()
            if (from.exists()) Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(staged.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING)
        connect(db).use { c ->
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
        return "restored a backup; the old database is $BEFORE"
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
