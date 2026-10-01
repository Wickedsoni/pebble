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
        PebbleDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), PebbleDatabase.Schema))

    fun inMemory(): PebbleDatabase =
        PebbleDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), PebbleDatabase.Schema))
}
