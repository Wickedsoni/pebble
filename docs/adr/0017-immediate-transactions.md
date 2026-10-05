# ADR 0017: Begin every transaction IMMEDIATE with a local copy of the SQLDelight driver

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** fix after PR #40 (not in the master plan)

## Context

The database runs in WAL mode. The event writer saves on its own thread (WP B5).
Two transactions read and then write: the history roll-up and `ReminderRepository.addLinked`.
They failed with `SQLITE_BUSY`:
- "history roll-up failed" in `pebble.log` on 4 and 5 Oct 2026;
- `CommandExecutorTest` in CI on PR #40.

A deferred transaction reads an old snapshot. If another connection writes before it, its write fails at once with `SQLITE_BUSY_SNAPSHOT`. The `busy_timeout` does not help in this case.

We examined the pinned jars:
- sqlite-jdbc 3.53.4.0 has the property `transaction_mode=IMMEDIATE`.
- SQLDelight 2.4.0 does not use it. `JdbcSqliteDriverConnectionManager.beginTransaction` runs a literal `BEGIN TRANSACTION`.
- `JdbcSqliteDriver` is final, and its connection manager is private.

PR #41 added an opt-in helper (`writeTransaction`) at the two call sites. A new call site can forget it, and then the error comes back.

## Decision

1. `PebbleSqliteDriver` (`shared/src/jvmMain/.../db/`) is a copy of the file-database part of SQLDelight 2.4.0's `JdbcSqliteDriver`: one connection for each thread, the same listeners, and the same schema create and migrate. One line is different: `BEGIN IMMEDIATE TRANSACTION`.
2. `DatabaseFactory.create` and `DatabaseFactory.inMemory` use it. Thus every transaction takes the write lock at its start, and another writer waits for the busy timeout.
3. We remove the `writeTransaction` helper and the `takeWriteLock` query. The call sites use `db.transaction` again.
4. **An update of SQLDelight needs a manual check.** The test `theCopiedDriverMatchesThePinnedSqlDelight` fails when the jar is not 2.4.0. Before you change that version, compare `PebbleSqliteDriver` with the new `JdbcSqliteDriver.kt` and `JdbcSqliteSchema.kt`.

## Consequences

- No call site must remember a rule. A new transaction that reads first is safe.
- A transaction that only reads also takes the write lock. Pebble has no such transaction. Single queries outside a transaction do not change, and WAL readers do not wait.
- Two transactions on different threads now run one after the other. Each one is short, and the busy timeout is 5 s.
- We own about 80 lines that copy SQLDelight. They do not get SQLDelight's fixes. The version test makes an update stop in CI until a person checks the copy.
- The `ConcurrentWriteTest` control test shows that SQLDelight's own driver still fails. If an update makes it pass, SQLDelight has changed. Then we can examine whether we still need the copy.

## Alternatives

- **The `writeTransaction` helper at each call site (PR #41).** We replaced it. It is opt-in, and a new call site can forget it.
- **sqlite-jdbc `transaction_mode=IMMEDIATE`.** We tried it, and it has no effect, because SQLDelight sends its own `BEGIN`.
- **A JDBC proxy that changes the `BEGIN` text.** We did not select it. It hides a change in the SQL, and it is harder to read than a small driver.
- **Retry on `SQLITE_BUSY_SNAPSHOT`.** We did not select it. Each call site needs the retry, and the transaction body runs again.
- **Upgrade SQLDelight.** We did not select it. The version that we use has this behaviour, and the plan does not name an upgrade.
