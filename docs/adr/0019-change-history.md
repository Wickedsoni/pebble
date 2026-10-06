# ADR 0019: Keep old versions and lost edits on this device for 90 days

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** E3c-1 (spec `docs/specs/E3C-HISTORY.md`, approved 2026-10-05)

## Context

The change journal (ADR 0018) keeps only the newest value of each synced field. A newer edit, or a peer's edit that wins a merge, replaces the old value. The merge (E3b) skips a peer's edit that loses. The only way back to an old value was a full restore of a backup.

## Decision

1. **Table `change_history`** (`ChangeHistory.sq`, migration `14.sqm`, schema 14 → 15): one row for each old value of one synced field, with its HLC, the HLC that replaced it (`replaced_by`), the time Pebble kept it (`kept_at`) and the kind (`replaced` or `lost`). The key `(tbl, uid, field, hlc)` is unique.
2. **A trigger keeps replaced values:** `change_journal_keep_history`, `BEFORE INSERT ON change_journal`. All paths write the journal with `INSERT OR REPLACE` (`record`, the merge, `reconcile`), so no code must remember it (the lesson of ADR 0017).
   - Checked with sqlite-jdbc 3.53.4.0 (SQLite 3.53.4): `BEFORE INSERT` fires for `INSERT OR REPLACE`; the same HLC again keeps nothing.
   - `deleted_at` is not kept: a delete is final.
3. **The merge keeps lost edits** (`ChangeJournal.keepLost`, in the merge transaction): a peer's field that loses, if it is not `deleted_at`, its value differs from this row's value, and its HLC is not from this device. A peer that sends back an old value of this device makes no lost edit.
4. **`ChangeHistory`** (`dev.pebble.core.sync`):
   - `versions` groups replaced entries by `replaced_by` and lost entries by `hlc`, newest first;
   - `restore` writes all fields of the version that differ (also "done" and "archived"), never `deleted_at`, through the new `ChangeJournal.edit` (journal entries with one new HLC, then the merge's update path). It returns false for a deleted item;
   - `restoreCopy` makes a new item with a new uid through its repository. The deleted item stays deleted. A note comes back not archived, a reminder not done;
   - `recentlyDeleted`, `lostSince`, `clear`, `purge`.
5. **Purge:** the daily IO job (`PebbleApp.rollUpHistory`) calls `history.purge` after the tombstone purge. It deletes entries kept more than 90 days ago, and all entries of rows that are gone.
6. **History never syncs.** It is not in `SyncTable` and not in `changesSince`. The encrypted backup includes it, because it is in `pebble.db`.

## Consequences

- An edit can be undone after a sync conflict, and a deleted item can come back as a copy, for 90 days.
- Old text stays in `pebble.db` for up to 90 days after an edit. `PRIVACY.md` says so. "Clear history" (E3c-2) removes it at once.
- Each journal write does one more indexed lookup. The tests show no change: `apply` of 500 rows stays in the E3b limit (< 200 ms).
- History starts empty at the migration: values replaced before schema 15 are not kept.
- If the history of a value is purged or cleared, and a peer then sends back that old value of a third device, the merge keeps it as a lost edit. This is rare and safe (only one more version to see).
- A copied reminder whose time has passed alerts at once as overdue (`ReminderEngine` fires every pending one-off with `due_at` in the past). The maintainer accepted this on 2026-10-05: a copy must show on the pages; you can reschedule it.
- Differences from the spec: `restoreCopy` returns `String?` (null if the item is not a tombstone here, for example after the purge). The note repository is `NoteRepository` in `WellnessRepository.kt`. `CalendarRepository.anyByUid` was added to read a deleted event.

## Alternatives

- **Write history in Kotlin at each call site.** Not selected: a new write path could forget it (ADR 0017).
- **Keep history in the journal (more rows per field).** Not selected: `changesSince` and the merge read one entry per field; a second kind of row in that table makes every sync query more complex, and history must never sync.
- **Sync history to peers.** Not selected: the spec keeps it on this device (privacy, and the same 90-day limit as tombstones).

## Revision (QA round 1, schema 16)

- **The same value is not a version.** The trigger `change_journal_keep_history` keeps an old value only if it differs from the new value. Two devices that make the same change (for example, they both archive a note) leave no History version that is equal to the current value. `15.sqm` replaces the trigger.
- `15.sqm` also drops the indexes `change_journal_row` and `change_history_row`. Each one repeats the first columns of the `UNIQUE` index of its table. It adds `event_log_at` on `event_log(at_millis)` for the daily roll-up.
