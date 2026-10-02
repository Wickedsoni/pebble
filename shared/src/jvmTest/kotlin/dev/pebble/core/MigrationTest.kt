package dev.pebble.core

import dev.pebble.core.brain.CommandFeedbackRepository
import dev.pebble.core.db.DatabaseFactory
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opening an older database must migrate it in place, keeping its rows. */
class MigrationTest {
    @Test
    fun version4DatabaseGainsFeedbackOutcome() {
        val file = Files.createTempFile("pebble-v4", ".db").toFile().apply { deleteOnExit() }
        // A v4 database as the previous release left it: command_feedback without outcome.
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE command_feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, chosen_action TEXT NOT NULL, model_intent TEXT, model_confidence REAL, at_millis INTEGER NOT NULL)")
                s.execute("INSERT INTO command_feedback(text, chosen_action, at_millis) VALUES ('tum cute ho', 'chitchat', 1)")
                s.execute("PRAGMA user_version = 4")
            }
        }
        val repo = CommandFeedbackRepository(DatabaseFactory.create(file))
        assertEquals(listOf("tum cute ho" to "picked"), repo.all().map { it.text to it.outcome })
    }

    /** Dry run on a *copy* of this machine's real database, if there is one. */
    @Test
    fun realDatabaseCopyMigrates() {
        val real = File(DatabaseFactory.defaultDataDir(), "pebble.db").takeIf { it.exists() } ?: return
        val copy = Files.createTempFile("pebble-real-copy", ".db").toFile().apply { deleteOnExit() }
        real.copyTo(copy, overwrite = true)
        val db = DatabaseFactory.create(copy)
        CommandFeedbackRepository(db).all() // reads the new column: fails if the migration didn't run
        DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); assertEquals(5, r.getInt(1)) } }
        }
    }
}
