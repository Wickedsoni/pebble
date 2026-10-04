# ADR 0003: Add a uid column and keep the INTEGER primary key

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone E (data that is ready for sync)

## Context

Sync between devices needs an id that is unique on all devices.
`one_off_reminder.id` is an `INTEGER AUTOINCREMENT` key (`Reminders.sq:13-19`).
`ReminderEngine` uses this `Long` id in its keys: `oneOffKey(r.id)` (`ReminderEngine.kt:61,91,195`).
The undo code also uses the `Long` id.

## Decision

We will keep the `INTEGER` primary key.
We will add a column `uid TEXT` with a unique index. Sync will use `uid`.

## Consequences

- The change is additive. The engine, undo and existing tests do not change.
- Each synced table has two ids. Use `id` on the device, and `uid` between devices.
- The migration must give a `uid` to each existing row.

## Alternatives

- **Change the primary key to a UUID.** We did not select it. It changes the engine keys, undo and many tests, and it gives no extra value.
