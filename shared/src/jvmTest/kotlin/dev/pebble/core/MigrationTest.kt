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
                // Every real v4 database has these (1.sqm); 11.sqm (WP E1) alters them.
                s.execute(
                    "CREATE TABLE note (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)",
                )
                s.execute(
                    "CREATE TABLE one_off_reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, due_at INTEGER NOT NULL, strictness TEXT NOT NULL, done_at INTEGER)",
                )
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
                s.execute(EVENT_LOG) // every real database has it; 15.sqm adds an index to it
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

    /** 10.sqm (WP C2): an older database gains an empty vector_item table; its notes become searchable. */
    @Test
    fun olderDatabaseGainsTheVectorTable() {
        val file = Files.createTempFile("pebble-v1v", ".db").toFile().apply { deleteOnExit() }
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
                s.execute("PRAGMA user_version = 1")
            }
        }
        val db = DatabaseFactory.create(file)
        assertEquals(0L, db.vectorsQueries.vectorCount().executeAsOne())
        dev.pebble.core.wellness.NoteRepository(db).add("buy milk", 1)
        assertEquals(listOf("buy milk"), db.vectorsQueries.sourceNotes().executeAsList().map { it.text })
    }

    /** 11.sqm (WP E1): notes and one-off reminders gain uid (unique, backfilled) and the sync columns; rows stay. */
    @Test
    fun version11RowsBecomeSyncReady() {
        val file = Files.createTempFile("pebble-v11", ".db").toFile().apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute(
                    "CREATE TABLE note (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)",
                )
                s.execute(
                    "CREATE TABLE one_off_reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, due_at INTEGER NOT NULL, strictness TEXT NOT NULL, done_at INTEGER)",
                )
                s.execute("INSERT INTO note(text, created_at, updated_at) VALUES ('buy milk', 1, 2), ('call plumber', 3, 4)")
                s.execute("INSERT INTO one_off_reminder(title, due_at, strictness) VALUES ('Call mom', 5000, 'NORMAL')")
                s.execute("INSERT INTO one_off_reminder(title, due_at, strictness, done_at) VALUES ('Old one', 100, 'NORMAL', 200)")
                s.execute(EVENT_LOG) // every real database has it; 15.sqm adds an index to it
                s.execute("PRAGMA user_version = 11")
            }
        }
        val db = DatabaseFactory.create(file)
        val notes = dev.pebble.core.wellness.NoteRepository(db)
        val reminders = dev.pebble.core.reminders.ReminderRepository(db)
        assertEquals(listOf("call plumber", "buy milk"), notes.recent(10).map { it.text })
        assertEquals(listOf("Call mom"), reminders.pendingOneOffs().map { it.title })
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                for (table in listOf("note", "one_off_reminder")) {
                    s.executeQuery("SELECT uid FROM $table").use { r ->
                        val uids = buildList { while (r.next()) add(r.getString(1)) }
                        assertEquals(uids.size, uids.toSet().size, "$table uids are unique")
                        uids.forEach { assertEquals(true, it.matches(Regex("[0-9a-f]{32}")), "$table uid $it") }
                    }
                }
                s.executeQuery("SELECT title, updated_at FROM one_off_reminder ORDER BY id").use { r ->
                    val rows = buildList { while (r.next()) add(r.getString(1) to r.getLong(2)) }
                    assertEquals(listOf("Call mom" to 5000L, "Old one" to 200L), rows, "updated_at backfilled from done_at, else due_at")
                }
            }
        }
    }

    /** 12.sqm (WP E2): a version-12 database gains the empty calendar table; its reminders stay and gain the event link. */
    @Test
    fun version12DatabaseGainsTheCalendar() {
        val file = Files.createTempFile("pebble-v12", ".db").toFile().apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute(
                    "CREATE TABLE one_off_reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, due_at INTEGER NOT NULL, strictness TEXT NOT NULL, done_at INTEGER, uid TEXT, updated_at INTEGER, deleted_at INTEGER, hlc TEXT, origin_device TEXT)",
                )
                s.execute("CREATE UNIQUE INDEX one_off_reminder_uid ON one_off_reminder(uid)")
                // Every real version-12 database has the note table (11.sqm); 13.sqm (WP E3) adds triggers to it.
                s.execute(NOTE_V11)
                s.execute(
                    "INSERT INTO one_off_reminder(title, due_at, strictness, uid, updated_at) VALUES ('Call mom', 5000, 'NORMAL', 'u1', 1)",
                )
                s.execute(EVENT_LOG) // every real database has it; 15.sqm adds an index to it
                s.execute("PRAGMA user_version = 12")
            }
        }
        val db = DatabaseFactory.create(file)
        assertEquals(listOf("Call mom"), dev.pebble.core.reminders.ReminderRepository(db).pendingOneOffs().map { it.title })
        val calendar = dev.pebble.core.calendar.CalendarRepository(db)
        assertEquals(emptyList(), calendar.live())
        calendar.save(dev.pebble.core.calendar.CalendarEvent("e", "Dentist", 10, 20, "Asia/Kolkata", remindMinutes = 15), at = 1)
        assertEquals(listOf("Dentist"), calendar.live().map { it.title })
        assertEquals(true, dev.pebble.core.reminders.ReminderRepository(db).addLinked("Dentist at 5", 5, "e", 10, at = 1))
    }

    /**
     * 13.sqm (WP E3a): a version-13 database gains the change journal and its guard triggers; its rows stay. At the
     * next start ChangeJournal.reconcile gives each synced row its entries and an HLC; a calendar-made reminder gets none.
     */
    @Test
    fun version13DatabaseGainsTheChangeJournal() {
        val file = Files.createTempFile("pebble-v13", ".db").toFile().apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute("INSERT INTO setting(key, value) VALUES ('device.id', 'aaaaaaaaaaaaaaaaaaaaaaaaaa')")
                s.execute(NOTE_V11)
                s.execute(
                    "CREATE TABLE one_off_reminder (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, due_at INTEGER NOT NULL, strictness TEXT NOT NULL, done_at INTEGER, uid TEXT, updated_at INTEGER, deleted_at INTEGER, hlc TEXT, origin_device TEXT, event_uid TEXT, occurrence_at INTEGER)",
                )
                s.execute(
                    "CREATE TABLE calendar_event (uid TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, notes TEXT, start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, all_day INTEGER NOT NULL DEFAULT 0, tz TEXT NOT NULL, rrule TEXT, exdates TEXT, remind_minutes INTEGER, owner_device TEXT, visibility TEXT NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'busy', 'full')), hlc TEXT, updated_at INTEGER NOT NULL, deleted_at INTEGER, origin_device TEXT)",
                )
                s.execute("INSERT INTO note(text, created_at, updated_at, uid) VALUES ('buy milk', 1, 2, 'n1')")
                s.execute("INSERT INTO note(text, created_at, updated_at, uid, deleted_at) VALUES ('old', 1, 3, 'n2', 3)")
                s.execute(
                    "INSERT INTO one_off_reminder(title, due_at, strictness, uid, updated_at) VALUES ('Call mom', 5000, 'NORMAL', 'r1', 1)",
                )
                s.execute(
                    "INSERT INTO one_off_reminder(title, due_at, strictness, uid, updated_at, event_uid, occurrence_at) VALUES ('Dentist', 900, 'NORMAL', 'r2', 1, 'e1', 1000)",
                )
                s.execute(
                    "INSERT INTO calendar_event(uid, title, start_at, end_at, tz, updated_at, owner_device) VALUES ('e1', 'Dentist', 1000, 2000, 'Asia/Kolkata', 1, 'aaaaaaaaaaaaaaaaaaaaaaaaaa')",
                )
                s.execute(EVENT_LOG) // every real database has it; 15.sqm adds an index to it
                s.execute("PRAGMA user_version = 13")
            }
        }
        val db = DatabaseFactory.create(file)
        val journal = dev.pebble.core.sync.ChangeJournal(db)
        assertEquals(listOf("buy milk"), dev.pebble.core.wellness.NoteRepository(db).recent().map { it.text })
        assertEquals(dev.pebble.core.sync.Reconciled(backfilled = 4, repaired = 0, graves = 0), journal.reconcile(10_000))
        assertEquals(emptyList(), journal.verify())
        assertEquals(dev.pebble.core.sync.Reconciled(0, 0, 0), journal.reconcile(20_000), "a second start changes nothing")
        val tables = db.journalQueries.entriesOfTable("one_off_reminder").executeAsList().map { it.uid }.toSet()
        assertEquals(setOf("r1"), tables, "the reminder that the event made is not synced")
    }

    /**
     * 14.sqm (WP E3c-1): a version-14 database gains `change_history` and the trigger that keeps replaced values. Its
     * history starts empty; the next edit keeps the old value.
     */
    @Test
    fun version14DatabaseGainsTheChangeHistory() {
        val file = Files.createTempFile("pebble-v14", ".db").toFile().apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute("INSERT INTO setting(key, value) VALUES ('device.id', 'aaaaaaaaaaaaaaaaaaaaaaaaaa')")
                s.execute(NOTE_V11)
                s.execute(
                    "CREATE TABLE change_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, uid TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, hlc TEXT NOT NULL, UNIQUE (tbl, uid, field))",
                )
                val hlc = "019a00000000.0000.aaaaaaaaaaaaaaaaaaaaaaaaaa"
                s.execute("INSERT INTO note(text, created_at, updated_at, uid, hlc) VALUES ('buy milk', 1, 2, 'n1', '$hlc')")
                listOf("text" to "\"buy milk\"", "created_at" to "1", "archived" to "0", "deleted_at" to "null").forEach { (f, v) ->
                    s.execute("INSERT INTO change_journal(tbl, uid, field, value, hlc) VALUES ('note', 'n1', '$f', '$v', '$hlc')")
                }
                s.execute(EVENT_LOG) // every real database has it; 15.sqm adds an index to it
                s.execute("PRAGMA user_version = 14")
            }
        }
        val db = DatabaseFactory.create(file)
        val history = dev.pebble.core.sync.ChangeHistory(db)
        val notes = dev.pebble.core.wellness.NoteRepository(db)
        assertEquals(emptyList(), history.versions(dev.pebble.core.sync.SyncTable.NOTE, "n1"))
        notes.update(notes.recent().single().id, "buy oat milk", 0x019a00000010L)
        assertEquals(
            listOf(mapOf("text" to kotlinx.serialization.json.JsonPrimitive("buy milk"))),
            history.versions(dev.pebble.core.sync.SyncTable.NOTE, "n1").map { it.fields },
        )
    }

    /**
     * 15.sqm (QA round 1): a version-15 database loses the two indexes that repeat a UNIQUE prefix, gains
     * `event_log_at`, and its history trigger no longer keeps a value that did not change.
     */
    @Test
    fun version15DatabaseGetsTheQaRound1Schema() {
        val file = Files.createTempFile("pebble-v15", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.create(file) // the current schema; the next lines turn it back into version 15
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TRIGGER change_journal_keep_history")
                s.execute("DROP INDEX event_log_at")
                s.execute("CREATE INDEX change_journal_row ON change_journal(tbl, uid)")
                s.execute("CREATE INDEX change_history_row ON change_history(tbl, uid)")
                s.execute(
                    "CREATE TRIGGER change_journal_keep_history BEFORE INSERT ON change_journal BEGIN " +
                        "INSERT OR IGNORE INTO change_history(tbl, uid, field, value, hlc, replaced_by, kept_at, kind) " +
                        "SELECT tbl, uid, field, value, hlc, new.hlc, 0, 'replaced' FROM change_journal " +
                        "WHERE tbl = new.tbl AND uid = new.uid AND field = new.field AND hlc <> new.hlc AND field <> 'deleted_at'; END",
                )
                s.execute("PRAGMA user_version = 15")
            }
        }
        val db = DatabaseFactory.create(file)
        val names = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('index', 'trigger')").use { r ->
                    buildSet { while (r.next()) add(r.getString(1)) }
                }
            }
        }
        assertEquals(
            setOf("event_log_at", "change_journal_keep_history"),
            names.intersect(setOf("event_log_at", "change_journal_keep_history")),
        )
        assertEquals(emptySet(), names.intersect(setOf("change_journal_row", "change_history_row")))
        val journal = db.journalQueries
        journal.recordEntry("note", "n1", "text", "\"a\"", "000000000001.0000.aaaaaaaaaaaaaaaaaaaaaaaaaa")
        journal.recordEntry("note", "n1", "text", "\"a\"", "000000000002.0000.aaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals(emptyList(), db.changeHistoryQueries.historyOfRow("note", "n1").executeAsList(), "the same value is not kept")
        journal.recordEntry("note", "n1", "text", "\"b\"", "000000000003.0000.aaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertEquals(1, db.changeHistoryQueries.historyOfRow("note", "n1").executeAsList().size)
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

    private companion object {
        /** The event log, which each real database has (15.sqm adds an index to it). */
        const val EVENT_LOG = "CREATE TABLE event_log (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, payload TEXT NOT NULL, at_millis INTEGER NOT NULL)"

        /** The note table as 11.sqm (WP E1) left it. */
        const val NOTE_V11 =
            "CREATE TABLE note (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0, uid TEXT, deleted_at INTEGER, hlc TEXT, origin_device TEXT)"
    }
}
