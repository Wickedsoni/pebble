package dev.pebble.core.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.ConnectionManager.Transaction
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties
import kotlin.concurrent.getOrSet

/**
 * SQLDelight's `JdbcSqliteDriver` for a database file (2.4.0, its `ThreadedConnectionManager`: one connection per
 * thread), with one change: transactions begin **IMMEDIATE**, so each one takes the write lock at its start.
 *
 * Why (ADR 0017): the database runs in WAL, and the event writer saves on its own thread. A
 * deferred transaction that reads, and then writes after another connection committed, fails at once with
 * SQLITE_BUSY_SNAPSHOT; `busy_timeout` does not help. With IMMEDIATE, a second writer waits for the busy timeout.
 * SQLDelight's driver sends a literal `BEGIN TRANSACTION`, so sqlite-jdbc's `transaction_mode` has no effect, and
 * the class is final. When SQLDelight is updated, compare this file with its `JdbcSqliteDriver.kt` again.
 */
class PebbleSqliteDriver(private val url: String, private val properties: Properties) : JdbcDriver() {
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()
    private val transactions = ThreadLocal<Transaction>()
    private val connections = ThreadLocal<Connection>()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.let(toNotify::addAll) } }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN IMMEDIATE TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use(PreparedStatement::execute)
    }

    override var transaction: Transaction?
        get() = transactions.get()
        set(value) {
            val current = transactions.get()
            transactions.set(value)
            if (value == null && current != null) closeConnection(current.connection)
        }

    override fun getConnection(): Connection = connections.getOrSet { DriverManager.getConnection(url, properties) }

    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
        if (transaction == null) {
            connection.close()
            connections.remove()
        }
    }

    override fun close() = Unit

    companion object {
        /** Opens [url] and creates or migrates [schema] from `PRAGMA user_version`, as SQLDelight's own factory does. */
        fun open(url: String, properties: Properties, schema: SqlSchema<QueryResult.Value<Unit>>): PebbleSqliteDriver {
            val driver = PebbleSqliteDriver(url, properties)
            object : TransacterImpl(driver) {}.transaction {
                val version = driver.executeQuery(null, "PRAGMA user_version", ::firstLong, 0, null).value ?: 0L
                if (version == 0L) {
                    schema.create(driver).value
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null).value
                } else if (version < schema.version) {
                    schema.migrate(driver, version, schema.version).value
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null).value
                }
            }
            return driver
        }

        private fun firstLong(cursor: SqlCursor) = QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
    }
}
