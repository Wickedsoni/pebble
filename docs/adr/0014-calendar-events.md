# ADR 0014: Store each calendar event as one row with an RRULE, and expand it with java.time

- **Status:** Accepted (revised in WP E3a: a deleted event never comes back)
- **Date:** 2026-10-05
- **Work package:** E2 (calendar)

## Context

WP E2 adds a calendar: a month page, an agenda on the Today page, reminders before events, and ICS import and export. The rows must follow the sync rules of WP E1 (ADR 0013).

The plan puts `RecurrenceExpander` in `commonMain`. The `:shared` module has no date and time library in `commonMain` (`shared/build.gradle.kts`: coroutines, serialization and SQLDelight only). Time zones and DST need one. CLAUDE.md rule 3 does not let us add a dependency that the WP does not name.

## Decision

1. **One row for each event.** Table `calendar_event` (migration `12.sqm`). A repeating event keeps its rule as the RRULE text (`rrule`). Pebble does not store each occurrence.
   - Sync-ready columns: `uid` (primary key), `updated_at`, `deleted_at` (tombstone), `hlc`, `origin_device`. Also `owner_device` and `visibility` (`private` by default) for milestone F.
   - `exdates`: skipped occurrences (ICS `EXDATE`). This column is not in the plan. Without it, an imported series with skipped days shows days that you deleted in Google or Outlook. The "Skip day" button on the Calendar page also writes it.
   - `remind_minutes`: a reminder before each occurrence.
2. **The RRULE subset:** `FREQ=DAILY|WEEKLY|MONTHLY`, `INTERVAL`, `BYDAY` (WEEKLY, without numbers), `BYMONTHDAY` (MONTHLY, 1 to 31), `UNTIL` or `COUNT`, `WKST=MO`. Pebble keeps every other rule, shows the event one time with a warning, and exports the rule unchanged.
3. **Expansion in `:shared` jvmMain with `java.time`** (`RecurrenceExpander`, `RecurrenceRule`, `Ics`, `CalendarAgenda`). The repository stays in `commonMain`. This is the difference from the plan.
   - A timed event keeps its local time in its own zone (`tz`) across DST. A time in a spring-forward gap moves forward. A time that occurs twice uses the first one.
   - An all-day event is a set of dates. It starts at midnight in the zone of the person who looks.
   - A day that a month does not have (the 31st) is skipped (RFC 5545).
4. **Reminders are linked one-off reminders.** `one_off_reminder` gains `event_uid` and `occurrence_at`. Every 10 minutes, and after each change to an event, `CalendarAgenda` makes the missing reminders for the next 2 days. A change or a delete makes the pending linked reminders into tombstones, then makes them again. The `ReminderEngine` does not change.
5. **ICS is hand-written** (no dependency). Import reads `VEVENT` only: `TZID` as an IANA zone (a Windows zone name is read in your zone, with a count in the message), `EXDATE`, and `RECURRENCE-ID` (the changed occurrence becomes its own event; the series skips that day). Export writes `TZID` without a `VTIMEZONE` block. Google Calendar and Outlook accept IANA names.
6. **Quick Add uses explicit syntax only:** `event: dentist fri 5pm for 30 min`. The command model does not make events yet. That needs rows in a new eval file and a passing gate (plan, WP E2).
7. **Defaults:** an event lasts 1 hour. A timed event reminds you 15 minutes before. An all-day event has no reminder. An import gives its timed events the reminder that is selected on the page (15 minutes before, or "No reminder"). Reminders are made only 2 days ahead, so a large import does not make many reminders at one time.

## Consequences

- A repeating event is one row, so sync (milestone F) sends one change for a series.
- A change to one occurrence (other than "skip") is not possible on the page yet. An imported changed occurrence is its own event with the uid `<series uid>/<start millis>`. Its export does not link it to the series.
- `RDATE` is ignored. The event is counted as "shown once".
- When `:shared` gets a second target, the expander and the ICS code must move to `commonMain` with a date and time library.

## Alternatives

- **One row for each occurrence.** We did not select it. A series without an end has no last row, and an edit must change many rows.
- **kotlinx-datetime in commonMain.** We did not select it. The WP does not name it (CLAUDE.md rule 3), and `:shared` has only the `jvm()` target.
- **A library for ICS (ical4j).** We did not select it. The plan says "no new dependency", and ical4j is large for the subset that we need.

## Revision (WP E3a, 2026-10-05)

A delete always wins (`docs/specs/E3-CHANGE-JOURNAL.md`, D5, the maintainer's decision).
- `CalendarRepository.save` returns false for the uid of a deleted event, and changes nothing. Before E3, a save brought the tombstone back.
- The ICS import counts these events and shows "N events were deleted before and were not imported again".
- A trigger stops each write that sets `deleted_at` back to null.
