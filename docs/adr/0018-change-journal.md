# ADR 0018: Record each synced field in a change journal with an HLC

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** E3a and E3b. The design is in `docs/specs/E3-CHANGE-JOURNAL.md` (approved on 2026-10-05).

## Context

Sync (milestone F) must send the changes of a device to other devices. Then all devices must have the same data, in any order of delivery.
The rows are ready for sync since WP E1 (ADR 0013): `uid`, tombstones, an empty `hlc` column, `origin_device`.
The data lives in three tables: `note`, `one_off_reminder` and `calendar_event`. The repositories in `:shared` write them.

Facts that we found during the work:
- Your installed Pebble (v0.1.2) uses the same database file. It writes without a uid, without an HLC, and it deletes notes with `DELETE`. ADR 0013 promises that an older Pebble can still use the file.
- SQLDelight 2.4.0 resolves only lower-case `new.` and `old.` in a trigger. Upper-case `NEW.` gives "No table found with name NEW".

## Decision

1. **HLC** (`dev.pebble.core.sync.Hlc`): wall milliseconds (12 hex digits), counter (4 hex digits), device id. The text order is the clock order.
2. **One clock for each database.** `HlcClock` keeps no state. `last` is `max(hlc)` of the journal, read in the same IMMEDIATE transaction as the write (ADR 0017). Thus two `ChangeJournal` objects on one database never make the same HLC. The wall time is the `at` of the write, which comes from `AppEnv`.
3. **Change journal** (`change_journal`, migration `13.sqm`): one entry for each synced field of each synced row (`UNIQUE (tbl, uid, field)`). A newer value replaces the entry and gets a new `seq`. The values are JSON text.
4. **Writes:** the repository calls `ChangeJournal.record` (one HLC for the change), then writes the row with `hlc` = that HLC, in one transaction.
5. **Guard triggers:**
   - A write that sets a new `hlc` must have a journal entry with that HLC for each synced field that it changes. Else the trigger stops it.
   - A deleted row cannot come back (spec D5, the maintainer's decision).
   - A write that keeps the `hlc` passes. An older Pebble writes like this.
6. **`ChangeJournal.reconcile`** runs at each start (`PebbleApp.journal`):
   - rows from before E3 and rows of an older Pebble get entries for all fields (backfill);
   - a field that an older Pebble changed gets a new entry (repair);
   - a row that an older Pebble deleted with `DELETE` gets a grave.
   - Changes made without the journal are written to `pebble.log` as a warning.
7. **`ChangeJournal.verify`** lists each field where the journal and the table do not agree. The tests call it after writes.
8. **Calendar:** `CalendarRepository.save` returns false for a deleted uid. The ICS import counts these events and tells you (ADR 0014 is revised).
9. **No `:sync` module** (spec D1, ADR 0008 is revised).
10. **Merge (E3b):** `changesSince(cursor, limit)` gives whole rows; `limit` counts journal entries. `apply(batch, wall)` checks every name, HLC and value against `SyncTable` (each field has a type), and the drift limit, before it writes. Then it merges in one transaction: the larger HLC wins, and for `deleted_at` (deleted, HLC) wins.
    - The winning fields are written one HLC group at a time, oldest first. Each update then changes only fields that have entries with its own HLC, so the guard triggers also check the merge.
    - A new row is inserted with no `hlc`, then its entries are written and its `hlc` is set.
    - A row that this device removed (no row, but journal entries: a grave) drops the change.
11. **Epoch (E3b):** the setting `sync.journalEpoch` names this copy of the journal. `Backup.applyStaged` writes a new one, so a peer's old cursor reads from the start.

## Consequences

- A write path in new code that forgets the journal fails in the tests, because it sets a new HLC without entries. If it also forgets the HLC, `verify()` in its test shows it. In the app, `reconcile` repairs it at the next start and writes a warning.
- Your installed v0.1.2 keeps working on the database at schema 14. Its changes become local changes of this device at the next start of a new Pebble.
- A deleted event does not come back from an ICS file. Before E3 it did.
- Each write adds one query for `max(hlc)` and one journal entry for each field. `record` takes 82 µs for 3 fields on the developer laptop (`ChangeJournalTest`, no disk sync). A whole note write takes about 10 ms; most of that is the disk sync of the commit.
- The journal keeps a second copy of the text of notes, reminders and events. After the 90-day purge, only the grave of a deleted row stays.

- **Measured (E3b, developer laptop, warm):** `changesSince` of 500 rows takes 17 ms (spec: < 50 ms); `apply` of 500 rows takes 110 to 130 ms (spec: < 200 ms). The convergence test (300 sequences of 50 operations, 3 devices) passes; it runs for about 100 s.
- **Found (E3b):** outside a transaction, each query opens and closes a SQLite connection (about 2 ms; the behaviour of SQLDelight's `ThreadedConnectionManager`, copied in ADR 0017). This is a follow-up, not part of E3.

## Alternatives

- **Strict triggers for every write (the spec's first form).** We did not select it. The maintainer decided that an older Pebble must keep working on the same file. With strict triggers, v0.1.2 could not add, edit or delete notes and reminders.
- **Triggers that write the journal.** We did not select it. A trigger cannot make an HLC, and it cannot tell a merge from a local edit.
- **A clock object in memory.** We did not select it. Two objects on one database could make the same HLC. The journal's `max(hlc)` in an IMMEDIATE transaction is one clock for all writers.
