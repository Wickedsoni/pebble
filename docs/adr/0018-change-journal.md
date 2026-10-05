# ADR 0018: Record each synced field in a change journal with an HLC

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** E3a. The design is in `docs/specs/E3-CHANGE-JOURNAL.md` (approved on 2026-10-05).

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

## Consequences

- A write path in new code that forgets the journal fails in the tests, because it sets a new HLC without entries. If it also forgets the HLC, `verify()` in its test shows it. In the app, `reconcile` repairs it at the next start and writes a warning.
- Your installed v0.1.2 keeps working on the database at schema 14. Its changes become local changes of this device at the next start of a new Pebble.
- A deleted event does not come back from an ICS file. Before E3 it did.
- Each write adds one query for `max(hlc)` and one journal entry for each field. Measured on the developer laptop: about 0.16 ms for each note (9.96 ms against 9.81 ms; most of the time is the disk sync of the commit).
- The journal keeps a second copy of the text of notes, reminders and events. After the 90-day purge, only the grave of a deleted row stays.

## Alternatives

- **Strict triggers for every write (the spec's first form).** We did not select it. The maintainer decided that an older Pebble must keep working on the same file. With strict triggers, v0.1.2 could not add, edit or delete notes and reminders.
- **Triggers that write the journal.** We did not select it. A trigger cannot make an HLC, and it cannot tell a merge from a local edit.
- **A clock object in memory.** We did not select it. Two objects on one database could make the same HLC. The journal's `max(hlc)` in an IMMEDIATE transaction is one clock for all writers.
