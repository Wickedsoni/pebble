# Spec E3c: History and restore

- **Status:** Approved by the maintainer on 2026-10-05, with the answers in section 10.
- **Date:** 2026-10-05
- **Work package:** E3c (new; the maintainer's decision on 2026-10-05, during E3b). It depends on E3a and E3b (ADR 0018).
- **Author:** Opus. **Implementer:** Sonnet, in three PRs (section 9).

## 1. Goal

You can get back an older version of a calendar event, a note or a one-off reminder:
- an old version of your own edit;
- an edit that lost a sync conflict (another device edited the same field, and the newer edit won);
- an item that you deleted, as a new copy.

Today the change journal keeps only the newest value of each field (spec E3, D4). The only way back is a full restore of a backup.

## 2. Rules that do not change

- **A delete is final** (E3 spec, D5). A deleted item never comes back in place. "Restore" of a deleted item makes a **copy** with a new uid.
- **A restore is a new edit.** It gets a new HLC, goes into the journal, and syncs like any edit. Pebble never changes the past of the journal.
- **History stays on this device.** Sync never sends it. It is not in `SyncTable` and not in `changesSince`.
- Calendar-made reminders (`event_uid`) have no history, because they are not synced (E3 spec, D3).

## 3. Terms

Add to `docs/GLOSSARY.md`:

| Term | Meaning |
|---|---|
| **history entry** | One row in `change_history`: an old value of one synced field, and why Pebble kept it. |
| **version** | The history entries of one item that one change replaced, shown together. |
| **lost edit** | An edit from another device that lost a merge, because this device had a newer edit of the same field. |
| **restore** | Make an old version the current one, as a new edit. |
| **restore as a copy** | Make a new item with the fields of a deleted item. |

## 4. Data (PR E3c-1, migration `14.sqm`, schema 14 → 15)

```sql
CREATE TABLE change_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tbl TEXT NOT NULL,
    uid TEXT NOT NULL,
    field TEXT NOT NULL,
    value TEXT NOT NULL,       -- JSON, as in change_journal
    hlc TEXT NOT NULL,         -- the HLC of this old value
    replaced_by TEXT,          -- the HLC of the change that replaced it; null for a lost edit
    kept_at INTEGER NOT NULL,  -- when Pebble kept it (local time, for the 90-day purge)
    kind TEXT NOT NULL CHECK (kind IN ('replaced', 'lost')),
    UNIQUE (tbl, uid, field, hlc)
);
CREATE INDEX change_history_row ON change_history(tbl, uid);
CREATE INDEX change_history_kept ON change_history(kept_at);
```

### 4.1 Replaced values: a trigger

A `BEFORE INSERT` trigger on `change_journal` copies the entry that the new entry replaces:

```sql
CREATE TRIGGER change_journal_keep_history BEFORE INSERT ON change_journal
BEGIN
    INSERT OR IGNORE INTO change_history(tbl, uid, field, value, hlc, replaced_by, kept_at, kind)
    SELECT tbl, uid, field, value, hlc, new.hlc, CAST(strftime('%s', 'now') AS INTEGER) * 1000, 'replaced'
    FROM change_journal
    WHERE tbl = new.tbl AND uid = new.uid AND field = new.field AND hlc <> new.hlc AND field <> 'deleted_at';
END;
```

- A trigger is used, so that every path is covered: `record` (local edits), `apply` (merge winners) and `reconcile` (changes of an older Pebble). No code must remember it. This is the lesson of ADR 0017.
- **VERIFY** before you write the migration:
  - a `BEFORE INSERT` trigger fires for `INSERT OR REPLACE` (SQLite docs: "REPLACE conflict resolution"). Test it with the pinned sqlite-jdbc 3.53.4.0;
  - write `new.` in lower case (CLAUDE.md trap 12).
- `deleted_at` is not kept, because a delete is final.
- `INSERT OR IGNORE` with the unique key makes the same batch applied two times keep one row.

### 4.2 Lost edits: in the merge

`ChangeJournal.apply` (E3b) skips a remote field that loses (7.2 of the E3 spec). In E3c-1 the merge writes it to `change_history` with `kind = 'lost'`, `replaced_by = null`, in the same transaction:
- only fields other than `deleted_at`;
- only if the remote value is different from the local value (the same value is not a conflict);
- only for rows that exist here (not for a dropped row, E3b "grave").

### 4.3 Purge

The daily IO job (`PebbleApp`, with the tombstone purge) deletes:
- history entries with `kept_at` older than 90 days (`PebbleApp.TOMBSTONE_DAYS`);
- all history entries of a row that the tombstone purge removed. Thus the text of a deleted note leaves the database after 90 days, as before.

A "Clear history" action (section 6) deletes all history entries at once.

## 5. API (PR E3c-1, `dev.pebble.core.sync.ChangeHistory`)

```kotlin
data class Version(
    val table: SyncTable,
    val uid: String,
    val fields: Map<String, JsonElement>,  // the old values of this version
    val at: Long,                          // the wall time of the HLC of the old values
    val device: String,                    // the device that made the old values
    val lost: Boolean,                     // true: an edit that lost a sync conflict
)

class ChangeHistory(db: PebbleDatabase, journal: ChangeJournal) {
    fun versions(table: SyncTable, uid: String): List<Version>   // newest first
    fun restore(version: Version, wall: Long): Boolean            // false if the item is deleted
    fun recentlyDeleted(now: Long): List<DeletedItem>             // tombstones of the last 90 days, newest first
    fun restoreCopy(item: DeletedItem, wall: Long): String        // the new uid
    fun lostSince(time: Long): Int                                // for F4: "N edits from other devices lost"
    fun clear(): Long
    fun purge(before: Long): Long
}
```

- **Group into versions:** replaced entries with the same `replaced_by`; lost entries with the same `hlc`.
- **`restore`** brings back **all** fields of the version (also `done_at` and `archived`; the maintainer's answer 4), but never `deleted_at`. It writes only the fields that differ from the current values. It goes through `ChangeJournal.record` and the merge's update path, with a new local HLC. For a calendar event, the caller then calls `agenda.eventChanged` (as for a local edit).
- **`restoreCopy`** reads the tombstone row and makes a new item through the repository (`NoteRepository.add`, `ReminderRepository.addOneOff`, `CalendarRepository.save` with `CalendarRepository.newUid()`). A reminder whose time has passed comes back with its old time; you can change it after.

## 6. UI (PRs E3c-2 and E3c-3)

Follow `docs/UI-PATTERN.md` (state holder + stateless content).

| Where | What |
|---|---|
| **Calendar page**, a selected event | A "History" button. A dialog lists the versions, newest first: the time, "this device" or the device name, the changed fields (old → current), and "Lost in a sync conflict" where it applies. Each version has "Restore". |
| **Reminders page**, a one-off reminder | The same "History" button and dialog. |
| **Notes page**, a note | The same. The Notes page first moves to the state-holder pattern (the B7 roll-out for Notes). |
| **Memory page**, a new card "Recently deleted" | Deleted events, notes and reminders of the last 90 days, newest first, each with "Restore as a copy". The card also has "Clear history" (this deletes all old versions on this device; it asks first). |

The text in the dialog uses the glossary words: "version", "restore", "restore as a copy".

## 7. Tests

### E3c-1
- **Trigger:**
  - a local edit, a merge winner and a `reconcile` repair each keep the replaced value (one row each);
  - the same batch two times keeps one row;
  - `deleted_at` is never kept;
  - **VERIFY** that `INSERT OR REPLACE` fires the trigger.
- **Lost edits:** two replicas edit the same field, then exchange.
  - The device with the newer edit keeps the other edit in its history as `lost`.
  - The other device keeps its own old value as `replaced`.
  - A lost edit with the same value is not kept.
- **Versions:** grouping by `replaced_by` and by `hlc`; newest first.
- **Restore:**
  - it changes only the fields that differ, with a new HLC in the journal;
  - a second replica receives the restore by `apply`;
  - a restore of a deleted item returns false.
- **Restore as a copy:** for each table; the new item has a new uid; the deleted item stays deleted on all replicas.
- **Purge:** entries older than 90 days go; entries of purged rows go; "Clear history" removes all.
- **Convergence:** `ConvergenceTest` stays green; history is never in a batch.
- **Migration:** `MigrationTest` 14 → 15; `SchemaParityTest`.

### E3c-2 and E3c-3
- State holder tests for the dialog and the "Recently deleted" card, as `RemindersStateHolderTest` does.
- A live check in the built app: edit an event two times, open "History", restore the first version; delete it, "Restore as a copy" on the Memory page.

## 8. Privacy

- History keeps old text on this device for up to 90 days. This is the same limit as for tombstones (ADR 0013).
- It never leaves the device (it is not synced). The encrypted backup includes it, because it is in `pebble.db`.
- "Clear history" deletes it at once.
- Update `PRIVACY.md` in E3c-1: one line for history, under the existing line for deleted items.

## 9. Delivery

| PR | Content | Done when |
|---|---|---|
| **E3c-1** | `14.sqm`, the trigger, lost edits in the merge, `ChangeHistory`, the purge in the daily job, `PRIVACY.md`, glossary, ADR 0019 | The tests of E3c-1 pass. The built app migrates the real database 14 → 15 (backup first). |
| **E3c-2** | History dialog on the Calendar and Reminders pages; "Recently deleted" card on the Memory page | State holder tests; a live check in the built app (on a copy of the data). |
| **E3c-3** | Notes page to the state-holder pattern (B7), then its History button | The same. |

## 10. Decisions of the maintainer (2026-10-05)

| # | Question | Answer |
|---|---|---|
| 1 | Where is "Recently deleted"? | **One card on the Memory page**, for events, notes and reminders (section 6). |
| 2 | Keep old versions for 90 days? | Yes, the same as deleted items (4.3). |
| 3 | A "Clear history" button on the Memory page? | Yes (section 6; it asks first). |
| 4 | What does a restore bring back? | **Everything** in the version, also "done" and "archived". A delete is never undone in place (section 5). |

## 11. Revision (QA round 1, schema 16)

- The trigger `change_journal_keep_history` keeps an old value only if it differs from the new value. Two devices that make the same change leave no History version that equals the current value. `15.sqm` replaces the trigger.
- `15.sqm` drops the indexes `change_journal_row` and `change_history_row` (each repeats the first columns of the `UNIQUE` index of its table). It adds the index `event_log_at`.
- A grave of a purged row can come in a batch from a peer. The merge keeps it in the journal and keeps no History for it (see spec E3, section 7.3).
