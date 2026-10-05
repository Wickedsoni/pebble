// Schema SQL copied verbatim from a released version.
@file:Suppress("ktlint:standard:max-line-length")

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
                s.execute(
                    "CREATE TABLE command_feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, chosen_action TEXT NOT NULL, model_intent TEXT, model_confidence REAL, at_millis INTEGER NOT NULL)",
                )
                s.execute("INSERT INTO command_feedback(text, chosen_action, at_millis) VALUES ('tum cute ho', 'chitchat', 1)")
                s.execute(
                    "CREATE TABLE reminder_rule (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, interval_minutes INTEGER NOT NULL, strictness TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, active_from_minute INTEGER NOT NULL DEFAULT 480, active_to_minute INTEGER NOT NULL DEFAULT 1380, last_done_at INTEGER)",
                )
                // Old defaults (eyes every 20 min) and one rule the user changed (stretch every 30).
                s.execute(
                    "INSERT INTO reminder_rule(id, kind, title, interval_minutes, strictness) VALUES ('eyes', 'EYES', '20-20-20: look 20 ft away for 20 s', 20, 'GENTLE')",
                )
                s.execute(
                    "INSERT INTO reminder_rule(id, kind, title, interval_minutes, strictness) VALUES ('stretch', 'STRETCH', 'Stand up & stretch', 30, 'GENTLE')",
                )
                s.execute("PRAGMA user_version = 4")
            }
        }
        val db = DatabaseFactory.create(file)
        val repo = CommandFeedbackRepository(db)
        assertEquals(listOf("tum cute ho" to "picked"), repo.all().map { it.text to it.outcome })
        val rules = dev.pebble.core.reminders.ReminderRepository(db).rules().associateBy { it.id }
        assertEquals(60 to "Rest your eyes", rules.getValue("eyes").let { it.intervalMinutes to it.title }, "old default gets calmer")
        assertEquals(30, rules.getValue("stretch").intervalMinutes, "a value you chose is kept")
    }

    /** 9.sqm (WP B6): an older database gains daily_stat, empty, and keeps every log entry. */
    @Test
    fun olderDatabaseGainsDailyStatAndKeepsItsLog() {
        val file = Files.createTempFile("pebble-v1", ".db").toFile().apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE widget_layout (widget_id TEXT NOT NULL PRIMARY KEY, x INTEGER NOT NULL, y INTEGER NOT NULL, visible INTEGER NOT NULL DEFAULT 1)",
                )
                s.execute(
                    "CREATE TABLE event_log (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, payload TEXT NOT NULL, at_millis INTEGER NOT NULL)",
                )
                s.execute("CREATE INDEX event_log_type_at ON event_log(type, at_millis)")
                s.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute(
                    """INSERT INTO event_log(type, payload, at_millis) VALUES ('note_completed', '{"type":"note_completed","noteId":1,"atMillis":5}', 5)""",
                )
                s.execute("PRAGMA user_version = 1")
            }
        }
        val db = DatabaseFactory.create(file)
        assertEquals(0L, db.historyQueries.statSum("note_completed").executeAsOne())
        assertEquals(1, db.pebbleQueries.recentEvents(10).executeAsList().size)
        assertEquals(1L, dev.pebble.core.history.EventHistory(db) { it }.count("note_completed"))
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
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA user_version").use { r ->
                    r.next(); assertEquals(dev.pebble.db.PebbleDatabase.Schema.version.toInt(), r.getInt(1))
                }
            }
        }
    }
}
