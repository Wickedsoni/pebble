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

    fun inMemory(): PebbleDatabase =
        PebbleDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), PebbleDatabase.Schema))
}
