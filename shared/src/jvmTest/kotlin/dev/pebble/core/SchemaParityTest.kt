package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A database upgraded through every migration must end up exactly like a fresh one. Catches a `.sqm`
 * that forgot a column or index the `.sq` files declare (or the other way round).
 */
class SchemaParityTest {
    @Test
    fun migratedFromVersion1MatchesFresh() {
        val fresh = tempDb("pebble-fresh").also { DatabaseFactory.create(it) }

        // Version 1 as the first release created it: the tables in Pebble.sq. 1.sqm adds the rest.
        val migrated = tempDb("pebble-v1")
        connect(migrated).use { c ->
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
        DatabaseFactory.create(migrated)

        val want = describe(fresh)
        val got = describe(migrated)
        assertEquals(want.keys, got.keys, "tables and indexes")
        want.forEach { (name, shape) -> assertEquals(shape, got[name], name) }
    }

    private fun tempDb(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().apply {
        delete() // an empty file is a valid "new" database, but start from nothing like a real install
        deleteOnExit()
    }

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    /** Every table's columns and every index's columns, keyed by name. */
    private fun describe(file: File): Map<String, List<String>> = connect(file).use { c ->
        val objects = c.createStatement().use { s ->
            s.executeQuery(
                "SELECT type, name FROM sqlite_master WHERE type IN ('table', 'index') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ).use { r -> generateSequence { if (r.next()) r.getString(1) to r.getString(2) else null }.toList() }
        }
        objects.associate { (type, name) ->
            val pragma = if (type == "table") "table_info" else "index_info"
            val rows = c.createStatement().use { s ->
                s.executeQuery("PRAGMA $pragma('$name')").use { r ->
                    val cols = r.metaData.columnCount
                    generateSequence {
                        if (r.next()) (1..cols).joinToString("|") { "${r.metaData.getColumnName(it)}=${r.getString(it)}" } else null
                    }.toList()
                }
            }
            "$type $name" to rows
        }
    }
}
