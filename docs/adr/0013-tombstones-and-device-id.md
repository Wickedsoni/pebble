# ADR 0013: Keep deleted notes and reminders as tombstones, and give each device an id

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** E1 (sync-ready rows)

## Context

Sync (milestone F) must tell other devices about a delete. Before E1, `NoteRepository.delete` and `ReminderRepository.deleteOneOff` ran a hard `DELETE`, so the row and the fact that it was deleted were gone. Rows also had no id that is unique on all devices (ADR 0003 decided to add `uid`) and no record of the device that made them.

## Decision

1. **Columns:** migration `11.sqm` adds `uid`, `deleted_at`, `hlc` and `origin_device` to `note` and `one_off_reminder`, and `updated_at` to `one_off_reminder` (`note` had it). It backfills `uid` with `lower(hex(randomblob(16)))`, adds a unique index on `uid`, and sets a reminder's `updated_at` from `done_at`, else `due_at`.
2. **Tombstones:** a delete sets `deleted_at` and `updated_at`. Every read of these tables adds `deleted_at IS NULL`, including the memory search source.
3. **Purge:** the daily IO job deletes tombstones older than 90 days (`PebbleApp.TOMBSTONE_DAYS`). When sync exists, the purge waits until every peer has acknowledged the delete.
4. **Device id:** at the first start, `DeviceIdentity` makes 128 random bits in base32 (26 characters) and saves them as the setting `device.id`. New rows record it in `origin_device` with a subquery in the `INSERT`. At each start, rows without a `uid` or a device (made before E1, or by an older Pebble that still writes to the same file) get them.
5. **Time:** write methods take `at` from `AppEnv`. A null `at` (old callers and tests) uses SQLite's current time.
6. `hlc` stays empty until sync (WP E3) defines the hybrid logical clock.

## Consequences

- An undo ("Not what I meant") still removes the item from view; the row stays as a tombstone for up to 90 days. Your data is then deleted, not only hidden.
- A downgrade to v0.1.2 still works: the new columns are nullable, and the next start of a new version fills them.
- **Checked on the developer's real database (2026-10-05):** it migrated from 11 to 12 with `integrity_check` ok, all rows got unique uids and the device id, and a ✕ on the Reminders page left a tombstone.

## Alternatives

- **Hard delete plus a separate deletion log.** We did not select it. Sync would need to join two tables, and a log row could lose its link to the uid.
- **Purge at once after sync.** There is no sync yet. 90 days lets a device that was away catch up.
