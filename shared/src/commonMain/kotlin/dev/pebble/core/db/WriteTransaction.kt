package dev.pebble.core.db

import dev.pebble.db.PebbleDatabase

/**
 * A transaction that takes the write lock at its start. Use it when the body reads before it writes.
 *
 * SQLDelight begins every transaction DEFERRED (`BEGIN TRANSACTION`, SQLDelight 2.4.0), and the database runs in WAL.
 * A deferred transaction that reads, and then writes after another connection (the event writer) committed, fails
 * at once with SQLITE_BUSY_SNAPSHOT: `busy_timeout` does not help there. Here the first statement is a write that
 * changes nothing, so the lock is taken before any read, and another writer waits for the busy timeout instead.
 * A transaction that starts with a write does not need this. Call it outside other transactions: nested, it joins
 * the outer one, which may already have read.
 */
fun <R> PebbleDatabase.writeTransaction(body: () -> R): R = transactionWithResult {
    pebbleQueries.takeWriteLock()
    body()
}
