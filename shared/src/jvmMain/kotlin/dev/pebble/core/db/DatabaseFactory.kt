package dev.pebble.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.pebble.db.PebbleDatabase
import java.io.File
import java.util.Properties

object DatabaseFactory {
    /** `%APPDATA%\Pebble` on Windows, `~/.pebble` elsewhere. */
    fun defaultDataDir(): File {
        val base = System.getenv("APPDATA")?.let { File(it, "Pebble") }
            ?: File(System.getProperty("user.home"), ".pebble")
        return base.apply { mkdirs() }
    }

    fun create(file: File = File(defaultDataDir(), "pebble.db")): PebbleDatabase =
        PebbleDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", fileProperties(), PebbleDatabase.Schema))

    /**
     * sqlite-jdbc connection settings (names from `org.sqlite.SQLiteConfig.Pragma`, checked in 3.53.4.0):
     * WAL lets the event writer and the UI read and write at the same time; a busy writer makes others
     * wait up to 5 s instead of failing with SQLITE_BUSY. Copy the database with SQLite's backup, not a
     * file copy: recent changes may still be in `pebble.db-wal`.
     */
    private fun fileProperties() = Properties().apply {
        setProperty("journal_mode", "WAL")
        setProperty("busy_timeout", "5000")
        setProperty("foreign_keys", "true")
    }

    /**
     * A fresh, private database for tests. It is a temp file, not `:memory:`: SQLDelight gives an in-memory
     * database one connection for all threads, so the event writer and the roll-up (both on IO threads)
     * would collide with a transaction on the test thread. A file gets one connection per thread, as in the app.
     * No disk sync: it is thrown away.
     */
    fun inMemory(): PebbleDatabase {
        val file = File.createTempFile("pebble-test", ".db")
        file.delete()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).deleteOnExit() }
        val props = fileProperties().apply { setProperty("synchronous", "OFF") }
        return PebbleDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", props, PebbleDatabase.Schema))
    }
}
