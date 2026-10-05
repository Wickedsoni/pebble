# ADR 0004: Measure event_log first, then roll up old days and keep the raw rows

- **Status:** Accepted (revised in WP B6)
- **Date:** 2026-10-04
- **Work package:** B6 (history scaling)

## Context

The `event_log` table gets one more row for each log entry.
`GrowthEngine.stats()` counted log entries from the start of the history.
`MemoryEngine` also counts finished notes from the start of the history (`MemoryEngine.kt`, "You've finished N notes").
The offline IPS eval (WP C4) needs the raw `nudge_decided` and `reminder_acted` rows.

WP B6 measured a history of 200 000 log entries (about 8 years at 70 entries each day):
- `stats()` took 88 ms. With a decoded check of the reminder action, it took 158 ms. The limit is 50 ms.
- `MemoryEngine.learn()` took 82 ms. The limit is 200 ms.

## Decision

We roll up old days, and we keep all raw rows:
1. Migration `9.sqm` adds the table `daily_stat(day, type, key, count)`.
2. `HistoryCompactor` counts each whole day that is older than 7 days into `daily_stat`. It records how far it got in the setting `history.rolledUpUntil`. It does this in one transaction.
3. `EventHistory` reads `daily_stat` for the days before that time, and the raw rows after it. `GrowthEngine` and the notes count in `MemoryEngine` use `EventHistory`.
4. The compactor does not delete rows.

## Consequences

- `stats()` takes 31 ms on 200 000 log entries. The numbers do not change.
- The first roll-up of a long history takes about 1.4 s, one time, on the IO thread.
- The IPS eval and later features keep the full raw history.
- The database continues to grow, about 150 bytes for each log entry (about 30 MB after 8 years).
- A new count over all history must use `EventHistory`, not a raw query from 0.

## Alternatives

- **Roll up and delete raw rows older than 180 days.** We did not select it. It removes the evidence that the IPS eval needs. It also makes the finished-notes count wrong unless that count changes too.
- **Delete old rows after a fixed time, with no roll-up.** We did not select it. It makes the level of the pet lower.
- **Faster queries only, no new table.** We did not select it. The time still grows with the history.
