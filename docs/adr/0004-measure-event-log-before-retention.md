# ADR 0004: Measure event_log first, and roll up before a delete

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone B (growth performance)

## Context

The `event_log` table gets one more row for each log entry.
`GrowthEngine.stats()` counts log entries from the start of the history (`Growth.kt:76-100`).
`MemoryEngine` and the offline IPS eval also read the history.
If we delete old rows, the level of the pet becomes lower.

## Decision

First, we will measure the size of `event_log` and the time of `stats()` on a real database.
Before we delete a row, we will roll up its counts into a `daily_stat` table.
Then `stats()` will read `daily_stat` and the recent log entries.

## Consequences

- The pet keeps its level after a cleanup.
- We change the code only if the measurement shows a real problem.
- The roll-up adds one table and one migration.

## Alternatives

- **Delete old rows after a fixed time.** We did not select it. It makes the level of the pet lower, and it removes data that the eval needs.
