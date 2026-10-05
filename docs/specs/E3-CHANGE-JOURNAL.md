# Spec E3: Change journal and hybrid logical clock

- **Status:** Approved by the maintainer on 2026-10-05, with the answers in section 12. Changed during E3a with the maintainer's approval: D6 (older Pebble versions keep working). E3a and E3b are done (ADR 0018). E3c (history and restore) is planned, see 7.5.
- **Date:** 2026-10-05
- **Work package:** E3 (master plan, milestone E). It depends on E1 (ADR 0013), E2 (ADR 0014) and ADR 0017.
- **Author:** Opus. **Implementer:** Sonnet, in two PRs (E3a and E3b, see section 11).

## 1. Goal

Make the local data ready for sync, before Pebble opens any socket:
- each change to a synced row gets a hybrid logical clock (HLC) time;
- each change goes into a change journal, in the same transaction as the row;
- a merge function applies changes from another device. All devices get the same rows, in any order of delivery.

E3 opens **no socket** and sends no data. The transport and the pairing are milestone F.
The test of E3 is a convergence test with replicas in one process.

## 2. Decisions that change the master plan

The plan text for E3 is short. The code shows four facts that change it. The maintainer changed one rule (D5), and one more during E3a (D6).

| # | Plan | This spec | Reason |
|---|---|---|---|
| D1 | Make a `:sync` module and a `build-logic` plugin. | Put the code in `:shared`, package `dev.pebble.core.sync`. No new module, no `build-logic`. | The journal must be written in the transaction of the repositories, and they are in `:shared`. At E3, `:sync` has one consumer. ADR 0008 says: extract a module only for a second consumer. Extract `:sync` for `:hub` (WP F5). Revise ADR 0008. |
| D2 | "Repositories call it inside the same transaction." | Repositories call it, **and** SQLite triggers stop any write to a synced field that has no journal entry. | A rule that code must remember fails later (see the opt-in fix of PR #41, replaced by ADR 0017). |
| D3 | Sync all of `note`, `one_off_reminder`, `calendar_event`. | Rows of `one_off_reminder` with `event_uid` are **not** synced. | The calendar agenda makes these reminders on each device (ADR 0014). If they sync, each device gets copies from the others. |
| D4 | One journal row for each change. | One journal row for each **field**: a newer change replaces the older row and gets a new `seq`. | A peer needs only the newest value of each field. The journal then keeps its size near the size of the data. |
| D5 | "Delete wins over concurrent edit only if its HLC is greater." | **A delete always wins.** A deleted row never comes back, on any device. | The maintainer's decision (section 12, question 1). An item that you deleted must not come back because another device edited it later. |
| D6 | (E3a) Triggers stop every write to a synced field without a journal entry (6.2). | Triggers check only writes that set a **new** HLC. `ChangeJournal.reconcile` records, at each start, the changes made without the journal. Tests call `ChangeJournal.verify`. | The maintainer's decision during E3a. An older Pebble (the installed v0.1.2) writes to the same file and knows nothing of the journal. Strict triggers would stop its writes (ADR 0013 promises that it still works). |

## 3. Terms

Add these terms to `docs/GLOSSARY.md` in PR E3a:

| Term | Meaning |
|---|---|
| **HLC** (hybrid logical clock) | A time for a change: the wall-clock milliseconds, a counter and the device id. HLC times have a total order. Code: `Hlc`, `HlcClock`. |
| **synced field** | A column that sync copies to other devices. The allow-list in `SyncSchema` names each one. |
| **change journal** | The table `change_journal`. It keeps the newest value and HLC of each synced field of each row. |
| **journal entry** | One row in `change_journal`. |
| **cursor** | How far a peer read the change journal of this device: the journal epoch and a `seq`. |
| **journal epoch** | A random id of this copy of the journal. A restore from a backup gives a new epoch. |

## 4. The hybrid logical clock

### 4.1 Format

An HLC is a text of 44 characters: `WWWWWWWWWWWW.CCCC.<device id>`.
- `W`: wall-clock milliseconds since 1970, 12 lower-case hex digits.
- `C`: the counter, 4 lower-case hex digits (0 to 65 535).
- The device id: the setting `device.id`, 26 characters of lower-case base32 (`DeviceIdentity`).

All parts have a fixed width. Thus the text order is the same as the order of (wall, counter, device id).
SQL can compare HLCs as text. Kotlin compares them with `Hlc.compareTo`.
`Hlc.parse` refuses a text that does not have this exact format.

### 4.2 Clock rules

`HlcClock(device, wall: () -> Long, start: Hlc?)` keeps `last`, the largest HLC that it made or received.
- **Start:** `start` = `SELECT max(hlc) FROM change_journal`. A restart thus never gives a smaller HLC.
- **`send()`** for a local change:
  - if `wall()` > `last.wall`: the result is (`wall()`, 0, device);
  - else: the result is (`last.wall`, `last.counter + 1`, device);
  - if the counter is more than 65 535: the result is (`last.wall + 1`, 0, device).
- **`receive(remote)`** for a change from a peer, after the drift check:
  - `last` = max(`last`, remote). The next `send()` is then larger than all known HLCs.
- **Drift check:** if `remote.wall` > `wall()` + 60 minutes, `receive` fails with `ClockAheadException`.
  - The merge then refuses the whole batch (section 7.4).
  - Reason: a peer with a clock in the year 2099 would win all conflicts for ever.
- The clock is thread-safe (`synchronized`). It gets the wall time from `AppEnv.clock`, never from `System.currentTimeMillis()`.

### 4.3 Clock tests (E3a)

Property tests with `kotlin.random.Random(seed)`, 10 000 steps for each seed:
- `send()` always gives a larger HLC than the one before, also when the wall clock goes back;
- `receive(x)` then `send()` gives an HLC larger than `x`;
- two clocks with different device ids never give the same HLC;
- `parse(format(h)) == h`, and text order equals `compareTo` order;
- a remote HLC more than 60 minutes ahead fails; one 59 minutes ahead passes.

## 5. Synced fields (allow-list)

`SyncSchema` (Kotlin object) is the only list. The merge refuses a table or a field that is not in it.

| Table | Key | Synced fields |
|---|---|---|
| `note` | `uid` | `text`, `created_at`, `archived`, `deleted_at` |
| `one_off_reminder` (only rows with `event_uid IS NULL`) | `uid` | `title`, `due_at`, `strictness`, `done_at`, `deleted_at` |
| `calendar_event` | `uid` | `title`, `notes`, `start_at`, `end_at`, `all_day`, `tz`, `rrule`, `exdates`, `remind_minutes`, `visibility`, `owner_device`, `deleted_at` |

These columns are **local** and never synced:
- `id` (each device has its own `INTEGER` key, ADR 0003);
- `hlc` (the HLC of the last write to this row on this device);
- `updated_at` (the merge sets it, see 7.3);
- `origin_device` (the device that made the row here; the HLC of the first change also shows it);
- `event_uid` and `occurrence_at`.

No other table is synced in E3. Examples: `reminder_rule`, `water_log`, `setting`, `memory`, `event_log`. Milestone F (F0, F4) decides more tables, with an allow-list.

**Scope is not E3.** The journal keeps all synced fields, also of `private` events. The sender filters by scope in F4.

## 6. The change journal

### 6.1 Table (migration `13.sqm`, schema 13 → 14)

```sql
CREATE TABLE change_journal (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    tbl TEXT NOT NULL,
    uid TEXT NOT NULL,
    field TEXT NOT NULL,
    value TEXT,            -- JSON: a string, a number or null
    hlc TEXT NOT NULL,
    UNIQUE (tbl, uid, field)
);
CREATE INDEX change_journal_row ON change_journal(tbl, uid);
```

- `seq` comes from `AUTOINCREMENT`, so SQLite never uses a `seq` two times.
- A new value for a field uses `INSERT OR REPLACE`. SQLite removes the old entry and adds a new one with a larger `seq`.
- `value` is the JSON form of the column value: `json_quote` in SQL, `JsonPrimitive` in Kotlin. Booleans are the integers 0 and 1, as in the tables.
- Add the setting key `sync.journalEpoch` (`SettingsRepository.Keys`). `ChangeJournal` makes it at the first start: 128 random bits, base32, as `DeviceIdentity.newId`.

Obey trap 4 in `CLAUDE.md`: `13.sqm`, the `.sq` file, a `MigrationTest` case, and `SchemaParityTest` stays green.

### 6.2 Guard triggers (D2)

`13.sqm` and the `.sq` files add one guard trigger for each table and each operation (`INSERT`, `UPDATE`):
- **Update:** the trigger checks each synced field `f` that changes (`NEW.f IS NOT OLD.f`).
  - It looks for the entry (`tbl`, `NEW.uid`, `'f'`) with `hlc = NEW.hlc` in `change_journal`.
  - If there is no such entry, it stops the write: `RAISE(ABORT, '<tbl>.<f> changed without a journal entry')`.
- **Insert:** if one synced field has no entry with `hlc = NEW.hlc`, then `RAISE(ABORT, ...)`.
- For `one_off_reminder`, the triggers apply only `WHEN NEW.event_uid IS NULL`.
- **A delete is final:** if `OLD.deleted_at IS NOT NULL` and `NEW.deleted_at IS NULL`, the update trigger stops the write: `RAISE(ABORT, '<tbl>: a deleted row cannot come back')`.
- The triggers do not check local columns. `claim`, `fillUids` and the 90-day purge thus work as before.

Thus a write path that forgets the journal fails at once, in the tests. It cannot fail quietly on a user's computer.
The order in each transaction is: first the journal entries, then the row with `hlc = <the same HLC>`.

### 6.3 Local writes (E3a)

`ChangeJournal.record(table, uid, values: Map<String, JsonPrimitive>): Hlc`:
1. It requires a transaction. If `driver.currentTransaction()` is null, it throws `IllegalStateException`.
2. It checks each field against `SyncSchema`.
3. It gets one HLC from `clock.send()` for the whole change.
4. It writes one journal entry for each field (`INSERT OR REPLACE`).
5. It returns the HLC. The repository then writes the row with that HLC.

Rules for the repositories:
- **Insert:** record **all** synced fields of the table. Make the `uid` in Kotlin (32 lower-case hex digits, the format of `lower(hex(randomblob(16)))`), so that the journal can use it before the row exists. The insert queries take `:uid` and `:hlc`.
- **Insert:** `deleted_at` is null, so the journal entry of `deleted_at` is `null`.
- **Update:** record only the fields that change. Do not record `deleted_at`.
- **Delete (tombstone):** record `deleted_at = <time>`.
- **`upsertEvent` changes (D5).** Today it brings a tombstone back (`deleted_at = NULL`, ADR 0014). After E3a:
  - for a live event, it records the synced fields that change;
  - for a uid that is deleted (a tombstone or a grave), it does nothing and returns false;
  - the ICS import counts these events and shows "N events were deleted before and were not imported again";
  - revise ADR 0014 for this change.

All write queries of the three tables get an `:hlc` parameter and set `hlc = :hlc`. The list today:
- `Wellness.sq`: `insertNote`, `updateNote`, `deleteNote`, `archiveNote`;
- `Reminders.sq`: `insertOneOff`, `deleteOneOff`, `markOneOffDone`, `rescheduleOneOff` (not `insertLinkedOneOff` or `deletePendingLinked`: linked rows are local);
- `Calendar.sq`: `upsertEvent`, `deleteEvent`.

Repositories get the `ChangeJournal` in the constructor (ADR 0009). `PebbleApp` makes one `HlcClock` and one `ChangeJournal`.

### 6.4 Rows from before E3, and writes of an older Pebble (E3a, D6)

In E3a this became `ChangeJournal.reconcile(wall)` (ADR 0018). It also records fields that an older Pebble changed, and gives a grave to a row that an older Pebble deleted with `DELETE`. The first form follows.

`ChangeJournal.claim()` runs at each start, after `DeviceIdentity` and the `claim` of the repositories:
- For each synced row with no `hlc` (`NULL` or `''`), it records all synced fields with one `send()`, and sets `hlc`. Linked reminders are not included.
- It is idempotent. A second run finds no rows.
- It runs in one transaction for each table.

### 6.5 Purge of tombstones

The daily job deletes tombstones older than 90 days (ADR 0013). In E3 it also deletes the journal entries of these rows, except the `deleted_at` entry. That entry is the **grave** of the row:
- The text of a deleted note then leaves the journal after 90 days, as it leaves the table.
- A late change for a row that has a grave and no row is dropped (7.3). With D5 this is also correct: the row was deleted.
- In F4, the purge waits until all peers acknowledged the delete (ADR 0013). Then a late change is rare.

## 7. Merge

### 7.1 Read changes (E3b)

`ChangeJournal.changesSince(cursor: Cursor?, limit: Int = 500): ChangeBatch`
- A null cursor, or a cursor with another epoch, means "from the start".
- It selects the journal entries with `seq > cursor.seq`, in `seq` order, up to `limit` (`limit` counts journal entries, not rows).
- **Whole rows:** for each (`tbl`, `uid`) in that selection, the batch has **all** journal entries of that row, also older ones. Thus the receiver can insert a row that it does not have. The merge is idempotent, so extra entries do no harm.
- `ChangeBatch(epoch, rows: List<RowChange>, next: Cursor)`, where `next.seq` is the largest selected `seq`. `RowChange(table, uid, fields: Map<String, Stamped(value, hlc)>)`.

### 7.2 Merge rule: last writer wins, field by field

For each field of each `RowChange`:
- If the local journal has no entry, or its HLC is smaller: the remote value wins.
- If the HLCs are equal: it is the same change. Do nothing (idempotence).
- If the local HLC is larger: the local value stays.

**Delete (D5).** The field `deleted_at` has its own rule. Order its values by (deleted, HLC): any non-null value wins over null, and between two non-null values the larger HLC wins. Thus:
- a delete wins over each edit, also an edit with a larger HLC;
- the other fields still merge by LWW, so the tombstone rows are the same on all devices;
- this order is a maximum over a total order, so the result does not depend on the order of delivery.

### 7.3 Apply (E3b)

`ChangeJournal.apply(batch): ApplyResult` runs in one transaction (it is `IMMEDIATE`, ADR 0017):
1. Check the batch. Refuse the whole batch (`ApplyResult.Refused(reason)`) if:
   - a table or a field is not in `SyncSchema`;
   - an HLC does not parse;
   - a value has the wrong type, or breaks a `CHECK` (for example `visibility`);
   - a row that is new here does not have all synced fields.
2. Call `clock.receive` for the largest HLC of the batch. If it fails, refuse the batch (`Refused(ClockAhead)`).
3. For each row:
   - **The row has a grave and no row (purged):** drop the change.
   - **The row is new here:** insert it with all fields. Set `hlc` = the largest field HLC. Then write the journal entries with their own HLCs.
   - **The row exists:** find the fields that win (7.2). Write their journal entries with their own HLCs. Then update those columns, and set `hlc` to the largest HLC of the fields that won.
4. Set `updated_at` = max(local `updated_at`, the wall time of the largest HLC that won).
5. Return `ApplyResult.Applied(rowsChanged, fieldsChanged, changedEvents)`. `changedEvents` is the list of `calendar_event` uids that changed.

The guard triggers also check the merge. A field that wins, but whose column has the same value, still gets its journal entry with the remote HLC. Thus all replicas keep the same HLC for each field.

The merge builds SQL only from the names in `SyncSchema`. It binds all values as parameters. A name from a peer is never put into SQL text.

### 7.4 Effects in the app

- `ReminderEngine` reads `pendingOneOffs` at each tick. A remote `done_at` or `deleted_at` thus stops the reminder.
- The agenda (`CalendarAgenda`, in `jvmMain`) makes the linked reminders on this device from the merged events. The caller of `apply` calls `agenda.eventChanged` for each uid in `changedEvents`, as for a local edit. The convergence test does this too.
- `apply` publishes no `PebbleEvent` in E3. F4 decides what the user sees.

### 7.5 Known limits (accept them)

- `exdates` is one text field. If two devices skip different days at the same time, one skip is lost. A set merge is possible later.
- LWW loses the older of two concurrent edits to the same field. A note edited on two devices keeps one text.
- Clocks that are wrong by less than 60 minutes can change which concurrent edit wins.

**E3c (planned, the maintainer's decision on 2026-10-05):** keep each value that a newer value replaced (your own edits and a value that lost a merge) for 90 days, on this device only. Each event, note and reminder gets a "History" view. You pick an old version, and Pebble restores it as a new edit, which then syncs. A deleted item can come back only as a copy with a new uid, because a delete is final (D5). E3c needs a short spec and the maintainer's approval first.

## 8. Journal epoch and restore (E3b)

A cursor is (`epoch`, `seq`). The `seq` of a restored journal can be smaller than a cursor that a peer keeps.
- `Backup.applyStaged` writes a new `sync.journalEpoch` into the restored database. It already keeps `device.id` there.
- A peer with a cursor of an old epoch then reads from the start. The merge is idempotent, so this is safe.
- Test: export, make changes, restore; `changesSince(old cursor)` starts from `seq` 0.

## 9. Tests

### E3a
- The clock tests of 4.3.
- **Guard:** for each table, a raw `UPDATE` of a synced field without a journal entry fails with the trigger message. A change of a local column (`origin_device`) does not fail. Linked reminders do not need entries.
- **Each write path:** each repository method of 6.3 writes the expected journal entries and sets `hlc`.
- **Claim:** a version 13 database with rows and no HLCs gets entries for all synced fields. A second `claim()` changes nothing.
- **Purge:** after the purge, only the `deleted_at` entry of a purged row stays.
- All existing tests stay green. Do not change their assertions (rule 5). Tests that write raw SQL to these tables must write through the repositories or add journal entries. Give the reason in the PR.

### E3b
- **Convergence (the gate of E3):**
  - 3 replicas, each a `DatabaseFactory.inMemory()` with real repositories and its own `HlcClock` (clock skew from −30 to +30 minutes);
  - 300 random sequences of 50 operations: add, edit, archive, done, reschedule, delete, re-import an event;
  - after each sequence, exchange batches in a random order, with random `limit`, duplicates and batches sent again;
  - assert that all replicas have the same synced fields for all rows, live and tombstones;
  - assert that all replicas have the same set of (`tbl`, `uid`, `field`, `hlc`) in the journal.
- **Delete wins (D5):** a delete and an edit with a larger HLC, delivered in both orders, on two replicas. The row stays deleted on both. A local write that sets `deleted_at` back to null fails with the trigger message.
- **ICS import of a deleted event:** the event stays deleted, and the import reports it.
- **Idempotence:** apply the same batch two times; the second apply changes nothing.
- **Refused batches:** an unknown field, a bad HLC, a wrong type, an HLC 61 minutes ahead, an incomplete new row. Nothing changes.
- **Linked reminders:** never in a batch.
- **Epoch:** section 8.
- **Speed:** measure on the developer laptop, and record the numbers in the PR.
  - `record` adds less than 1 ms to a write.
  - `changesSince` of 500 rows takes less than 50 ms.
  - `apply` of 500 rows takes less than 200 ms.

## 10. Privacy and security

- E3 opens no socket. The privacy invariant of `CLAUDE.md` does not apply yet. F2 and F7 apply it.
- The journal keeps a second copy of the text of notes, reminders and events. It stays in `pebble.db`, so the encrypted backup (ADR 0015) includes it.
- The text of a deleted item leaves the journal at the 90-day purge (6.5).
- The merge treats all input from a peer as hostile: the allow-list, type checks, bound parameters, and a drift limit (7.3). F0 adds the threat model.

## 11. Delivery

| PR | Content | Done when |
|---|---|---|
| **E3a** | `Hlc`, `HlcClock`, `SyncSchema`, `ChangeJournal.record` and `claim`, `13.sqm` with the table and the guard triggers, all write paths of 6.3 (with the `upsertEvent` change and a revised ADR 0014), the purge of 6.5, the glossary terms, the revised ADR 0008 and a new ADR 0018 (this design) | The tests of E3a pass. The built app starts on the real database: it migrates 13 → 14, `claim` fills the journal, and `integrity_check` is ok. |
| **E3b** | `changesSince`, `apply`, the epoch (8), the convergence test | The tests of E3b pass, including the convergence gate. |

Each PR obeys the Definition of Done in `CLAUDE.md`. Each PR adds its facts to the "Verified facts" table.

## 12. Decisions of the maintainer (2026-10-05)

| # | Question | Answer |
|---|---|---|
| 1 | Edit after delete: does the item come back? | **No. A delete always wins** (D5, sections 6.2, 6.3, 7.2). |
| 2 | Calendar reminders stay local (D3)? | Yes. |
| 3 | No `:sync` module in E3; `:sync` comes with `:hub` in F5 (D1)? | Yes. Revise ADR 0008 in E3a. |
| 4 | Clock drift limit of 60 minutes (4.2)? | Yes. |
| 5 | Accept that a skipped day can be lost (7.5)? | Yes. |
