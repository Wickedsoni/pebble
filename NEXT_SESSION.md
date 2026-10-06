# Pebble — status and next-session plan

To resume: open this repo and say "continue from NEXT_SESSION.md".
Public repo: https://github.com/Wickedsoni/pebble · latest release: **v0.1.2** (pre-release, 2 Oct 2026).

## Session handoff (5 Oct 2026) — read this first

**This file is current on `main`** (the PR stack was merged on 5 Oct 2026).

### Start here (6 Oct 2026, after "edit a note" and per-item history delete)
- **Retrain warning:** the dev/train split is now a hash of the sentence (QA round 1, P11), and the random stream changed. The next retrain sees a new corpus and new dev numbers. Do not compare its dev metrics with the old ones.
- **State:** A1 … E4, C4, E3a, E3b, all of **E3c** (#51–#54), "edit a note" (#55) and "delete old versions of one item" (#56) are on `main`. Schema version 15; the next migration file is `15.sqm`.
- **Next: milestone F.** F0 needs an Opus spec and a security review first (CLAUDE.md rule 9): do not start code without an approved spec.
- **Optional, suggested:** a small performance WP. Outside a transaction, each query opens a new SQLite connection (about 2 ms on this laptop, more on CI). Measure the app's queries, then keep connections open per thread (check the memory cost first). It would also make CI faster.
- **CI (5 Oct):** `ConvergenceTest` runs 60 sequences on a PR and the full 300 on a push to `main` or with "Run workflow". The PR Kotlin job takes about 6 to 7 min.
- **How each WP lands:**
  1. Branch `wp/<id>-<slug>` from `main`, then the tests.
  2. Build the distributable and **open and check the app** (no need to ask).
  3. PR → CI. Ask before you merge.
- **Waiting for your yes:** cut release **0.3.0** (`gh release`). It ships the v3 command model and now the calendar. Publish the chat pack and a speech pack with it: sign them with `./gradlew :desktopApp:modelPack`; the key is in `%USERPROFILE%\.pebble\signing\` (back it up offline).
- **Deferred by you:** C6 model training (Hindi/Hinglish chat distillation). Do not start it until you ask.
- **Your housekeeping:**
  - delete `%APPDATA%\Pebble\pebble-backup-e1.db`, `pebble-backup-e2.db`, `pebble-backup-e3a.db` and `pebble-backup-e3c.db` (taken before the E2, E3a and E3c-1 migrations) when you are happy;
  - the old local model folders `brain/models/intent-v0…v2*` can be deleted (ask Claude).

### 6 Oct: "Delete old versions" of one item (your choice, PR #56)
- **Why:** with note editing, private text that you edit out stays in History for 90 days. "Clear history" removed the history of every item at once.
- **What:** each History dialog has "Delete old versions" (asks first; "Backups you made before keep them"). It deletes only that item's history (`ChangeHistory.clear(table, uid)`); the item does not change. The dialog hint now says that Pebble keeps old versions for 90 days. `PRIVACY.md` names both ways to delete.
- **Tests:** `ChangeHistoryTest.clearOfOneItemRemovesOnlyItsHistoryAndKeepsTheItem`, `HistoryStateHolderTest.deleteOldVersionsAsksFirstThenRemovesOnlyThisItemsHistory`. Live check on a copy of your data: an event version, then "Delete old versions" → "Delete them" → "Deleted 1 old version".

### 6 Oct: edit a note (your request, PR #55, merged)
- **What:** an "Edit" chip on each note (Notes page) opens an edit field with the text; Enter saves, Escape cancels, one field at a time. A save goes through the new `PebbleApp.editNote` (a journaled `NoteRepository.update`, then the search index catches up), so the note's History keeps the old text. The same text, or a blank text, writes nothing. `GlassField` gets `initial`, `onCancel` (Escape) and `autoFocus`; the defaults keep every other field as it was.
- **Tests:** `NotesStateHolderTest` (9, 4 new).

### 6 Oct: WP E3c-3 done (Notes page to the state-holder pattern + History, PR #54, merged)
- **What:** `NotesStateHolder` + stateless `NotesContent` (the B7 roll-out for Notes). Add and complete go through `app::addNote` / `app::completeNote`, so the bus events are the same (checked in the event log). Times use `env.zone()` (before: `ZoneId.systemDefault()`). Each note with a uid has a "History" chip (the shared dialog). `Note.uid` added.
- **Tests:** `NotesStateHolderTest` (5). 336 Kotlin tests, 0 skipped.
- **Live check (on a copy of your data):** added two notes, opened History ("No older versions yet"), completed one; `note_created` and `note_completed` were logged.

### 6 Oct: agenda race fix for moved events (PR #53, merged)
- **What:** the 10-minute loop reads each event with its HLC (`CalendarRepository.betweenWithHlc`) and passes it to `addLinked(..., eventHlc)`. In its IMMEDIATE transaction, `addLinked` refuses when the event changed since (a new HLC: moved, edited) or was deleted. The event's own `eventChanged` makes the reminders of the new version. This closes the gap left by PR #51.
- **Test:** `CalendarTest.aReminderIsNotMadeForAnEventMovedAfterTheAgendaReadIt`. 331 Kotlin tests, 0 skipped. The built app started on a copy of your data; the loop made the event reminders; no new log lines.

### 5 Oct: WP E3c-2 done (History dialog + "Recently deleted", PR #52, merged)
- **What:** a "History" chip on each event (Calendar page) and each synced one-off reminder (Reminders page) opens a dialog: versions newest first, "this device" or "another device", "Lost in a sync conflict", the changed fields (old → now), and "Restore" (hidden when a version is the same as now). One shared `HistoryStateHolder` + `HistoryOverlay`. Memory page: a "Recently deleted" card (`RecentlyDeletedStateHolder`) with "Restore as a copy" and "Clear history" (asks first).
- **Small changes:** `OneOffReminder.uid` (null for calendar-made reminders: they have no history); `NoteRepository.activeFlow(context)` (the card needs the test dispatcher).
- **Tests:** `HistoryStateHolderTest` (7). 329 Kotlin tests, 0 skipped.
- **Live check (5 Oct, on a copy of your data, `APPDATA` = a temp folder):** daily event, two "Skip day" edits, History showed two versions, "Restore" of the first brought the skipped days back; deleted it; "Recently deleted" → "Restore as a copy" brought it back on the calendar; "Clear history" asked, then deleted 3 entries. Found and fixed in the check: "1 skipped days", and a clipped line on the card.

### 5 Oct: E3c-1 merged (PR #51)
- CI found a race from WP E2: the 10-minute agenda loop could add a reminder for an event deleted at the same moment. `addLinked` now refuses an event that is a tombstone (in its IMMEDIATE transaction). Test: `CalendarTest.aReminderIsNotMadeForAnEventDeletedAfterTheAgendaReadIt`.

### 5 Oct: WP E3c-1 done (history data + API, PR #51, merged)
- **What:** table `change_history` (`14.sqm`, schema 14 → 15) keeps old values of synced fields of events, notes and one-off reminders for 90 days, only on this device (ADR 0019).
  - A trigger on `change_journal` keeps each replaced value on every write path (local edit, merge, reconcile). `deleted_at` is never kept.
  - The merge keeps a peer's losing edit as a **lost edit** (not if the value is the same, not if the edit was this device's).
  - `ChangeHistory`: `versions`, `restore` (a new edit with a new HLC, through the new `ChangeJournal.edit`), `recentlyDeleted`, `restoreCopy` (new uid; the deleted item stays deleted), `lostSince`, `clear`, `purge`. The daily job purges history after the tombstone purge.
- **VERIFY done:** `BEFORE INSERT` fires for `INSERT OR REPLACE` with sqlite-jdbc 3.53.4.0 (SQLite 3.53.4).
- **Differences from the spec (in the PR and ADR 0019):** `restoreCopy` returns `String?` (null if the item is not a tombstone here); a copied note comes back not archived and a copied reminder not done.
- **Tests:** `ChangeHistoryTest` (15), `MigrationTest` 14 → 15. 322 Kotlin tests, 0 skipped. `apply` of 500 rows: 104 ms (limit 200 ms).
- **Live check (5 Oct):** backup first: `%APPDATA%\Pebble\pebble-backup-e3c.db` (schema 14, `integrity_check` ok). The built app migrated your real database 14 → 15; `integrity_check` ok; the history is empty (it starts at the migration); no new log lines. E3c-1 has no UI.

### 5 Oct: WP E3b done (merge + convergence gate, PR #47, merged)
- **What:** `ChangeJournal.changesSince(cursor, limit)` gives whole rows after a cursor; `ChangeJournal.apply(batch, wall)` merges a peer's batch: the larger HLC wins field by field, a delete always wins, all or nothing. Every name, type and HLC is checked first; a clock more than 60 minutes ahead is refused. A restore gives the journal a new epoch, so peers read again from the start.
- **Gate:** `ConvergenceTest`: 3 devices with skewed clocks, 300 sequences of 50 random changes (also the same event made on two devices, edits against deletes), exchanged in random orders and batch sizes with duplicates and late batches. All three agree after every sequence. It runs for about 100 s.
- **Speed (warm):** `changesSince` of 500 rows 17 ms (spec < 50); `apply` of 500 rows 110 to 130 ms (spec < 200).
- **Your decision (5 Oct): E3c, history and restore.** Keep replaced values for 90 days on this device; a "History" view on events, notes and reminders restores an old version as a new edit (a deleted item comes back as a copy). It also keeps the edit that loses a sync conflict. The spec is approved (PR #49).
- **Found:** outside a transaction, each query opens a new SQLite connection (about 2 ms). It is SQLDelight's own behaviour (copied in ADR 0017). Worth a small performance WP: measure the app's queries, then keep connections open per thread (check the memory cost first).
- **Live check:** E3b adds no UI and the app does not call the merge yet (F4 will). The built app started on your real database with no new log lines.

### 5 Oct: WP E3a done (change journal + HLC, PR #46, merged)
- **What:** every write to a synced field of a note, a one-off reminder or a calendar event goes into `change_journal` with a hybrid logical clock time (HLC), in the same transaction (ADR 0018, spec `docs/specs/E3-CHANGE-JOURNAL.md`). No socket; nothing leaves the PC.
- **Your decisions in it:**
  - **a delete always wins** (a trigger stops any un-delete; an ICS re-import skips deleted events and says so);
  - **calendar-made reminders stay local** (never journaled);
  - **older Pebble versions keep working** (D6): triggers check only writes that set a new HLC; `reconcile` at each start records what an older app changed, with a warning in `pebble.log`.
- **Found:** SQLDelight 2.4.0 accepts only lower-case `new.`/`old.` in triggers (trap 12 in CLAUDE.md).
- **Tests:** `HlcTest` (6, 10 000 steps each), `ChangeJournalTest` (10), `MigrationTest` 13→14, a Calendar page test. Four older tests changed, each for a stated reason (PR). 297 Kotlin tests, 0 skipped. Speed: `ChangeJournal.record` takes 82 µs for 3 fields (spec: < 1 ms). The first speed test compared whole commits and failed on CI (disk sync noise, 71 vs 42 ms); it now measures `record` alone.
- **Live check (5 Oct):**
  - backup first: `%APPDATA%\Pebble\pebble-backup-e3a.db` (schema 13, `integrity_check` ok). Delete it when you are happy.
  - your real database migrated 13 → 14; at start the log said "recorded 3 rows from before the journal": your note, your reminder and your event got their entries (4, 5, 12); the reminder that the event made got none. `integrity_check` ok.
  - on a temp copy of your data: Calendar → Add "Journal check 9pm", then ✕: the journal has the title and the later delete; the linked reminder was made and removed and never journaled.
  - Pebble v0.1.2 is no longer installed on this PC, so it was not started; its writes are covered by `ChangeJournalTest.reconcileRecordsWhatAnOlderPebbleChangedWithoutTheJournal`.

### 5 Oct: SQLITE_BUSY fix, now global (PR #41, then PR #43, both merged)
- **Problem:** two transactions read and then write: the history roll-up ("history roll-up failed" in `pebble.log` on 4 and 5 Oct) and `ReminderRepository.addLinked` (it failed once in CI on PR #40). SQLDelight begins every transaction deferred. In WAL such a transaction fails at once with `SQLITE_BUSY_SNAPSHOT` when the event writer committed after its read. `busy_timeout` does not help.
- **PR #41 (merged)** added an opt-in helper (`writeTransaction`) at the two call sites. You asked for a global fix, because new code could forget the helper.
- **Global fix (ADR 0017):** `PebbleSqliteDriver` is a copy of SQLDelight 2.4.0's `JdbcSqliteDriver` (about 80 lines) with `BEGIN IMMEDIATE TRANSACTION`. `DatabaseFactory` uses it, so every transaction takes the write lock at its start. The helper is removed.
  - sqlite-jdbc's `transaction_mode` has no effect, because SQLDelight sends its own `BEGIN` (checked in the jars).
- **SQLDelight updates:** `ConcurrentWriteTest.theCopiedDriverMatchesThePinnedSqlDelight` fails when SQLDelight is not 2.4.0. Before an update (also a Dependabot one), ask Claude to compare the copy with the new driver. The rule is in CLAUDE.md rule 3.
- **Tests:** `ConcurrentWriteTest` (6):
  - SQLDelight's own driver still fails (the control test);
  - every transaction makes the other writer wait, and holds the lock from `BEGIN`;
  - stress: `addLinked` + the roll-up 200 times while the event writer saves;
  - live queries still hear changes;
  - the version guard.
  - With a deferred `BEGIN` in the copy, the three lock tests fail with the CI errors.

### 5 Oct: WP C4 done (offline IPS evaluator, PR #39, merged)
- **What:** `NudgeIpsEvaluator` (`shared/.../brain/`) pairs each logged `nudge_decided` (with its propensity) with the reaction, as the engine scores it. It gives IPS, SNIPS and ESS for a fixed target policy (ADR 0016).
- **Gate (CLAUDE.md rule 8):** a change to the `NudgePolicy` priors or rewards must show SNIPS ≥ the mean reward of what ran, and ESS ≥ 200 (you decided on 5 Oct to keep the 200 bar, although no change can pass for some weeks). Run `./gradlew :desktopApp:nudgeIps` (it reads a `VACUUM INTO` copy of your database; the app's data is not touched).
- **Your data (5 Oct):** 45 decisions, 9 with a known outcome; ESS 8 → "NOT ENOUGH DATA". Most decisions are skipped because the app stopped or started before the reaction (88 starts in 4 days of test builds). A normal week of use will give more.
- **Event log:** read only, raw rows (a new query `eventsOfTypesSince`; no migration, schema stays 13). No row is deleted (ADR 0004).
- **Tests:** `NudgeIpsEvaluatorTest` (10): reward pairing (done, late, snooze, skip, ignored, restarts, other keys, bad entries); a hand-computed example; on-policy SNIPS = observed; SNIPS finds the true value of a rarely-run policy (error < 0.03); the gate refuses low ESS; the real `ReminderEngine` + log give exactly the rewards that the policy learned. All 273 Kotlin tests pass, 0 skipped.
- **Live check (built distributable, 5 Oct):** the built app started on your real database with no new warnings in `pebble.log`; `nudgeIps` ran against the database while the app had it open. A fullscreen video was in front of the window, so no screenshot of the UI (C4 has no UI change).

### 5 Oct: WP E4 done (encrypted backup, PR #37, merged)
- **About → Backup:** "Back up to a file…" writes `pebble-backup-<date>.pebblebackup` (asks the passphrase two times, 8+ characters); "Restore from a file…" checks the file and restores it at the next start (your current data is kept as `pebble.db.before-restore`); "Cancel the restore".
- **Format (ADR 0015):** AES-256-GCM in 1 MiB chunks (STREAM nonces, header as AAD), key PBKDF2-HMAC-SHA256 600 000 iterations (369 ms). Difference from the plan: chunks instead of one GCM message, so a large database never needs the whole file in the 256 MB heap.
- **Restore keeps this computer's `device.id`** (no two devices with one id after a move to a new PC).
- **Live check (built distributable, jlink runtime, 5 Oct):** a real backup of your database was written from About → Backup (225 KB, `PEBBLEBK` header); a wrong passphrase was refused; the right one staged the restore, and "Cancel the restore" removed it (your data was not replaced). Restore at start was checked with a second built app on a temp `APPDATA`: the log says "restored a backup". The test file was deleted. Found and fixed: the Help card on the About page cut its last line once the Backup card was added (it now scrolls).
- **Please try it once yourself:** back up to a USB stick or a cloud folder, and keep the passphrase in a password manager.
- **Tests:** `BackupTest` (10: sizes across chunk boundaries, wrong passphrase, cut files incl. at a chunk boundary, changed bytes, export → restore on "another computer", newer schema refused, cancel) and `BackupStateHolderTest` (5).

### 5 Oct: WP E2 done (calendar, PR #35, merged)
- **Data (ADR 0014):** migration `12.sqm` adds `calendar_event` with the E1 sync columns (uid primary key, tombstones, `hlc`, `origin_device`) and `owner_device` / `visibility` (`private`) for milestone F. `one_off_reminder` gains `event_uid` + `occurrence_at`: reminders before events are linked one-off reminders, made 2 days ahead.
- **Repeats:** the RRULE subset DAILY / WEEKLY (BYDAY) / MONTHLY (BYMONTHDAY), INTERVAL, UNTIL or COUNT. Other rules are kept and shown once with a warning. DST, the 31st and 29 Feb are tested.
- **Difference from the plan:** the expander and ICS code are in `:shared` **jvmMain** (`java.time`), because commonMain has no date library and the WP names no dependency. An `exdates` column was added (EXDATE), so imported skipped days and the new **"Skip day"** button work.
- **UI:** Calendar page (month grid + the selected day; Add field uses the Quick Add syntax; chips for repeat and reminder; Import / Export .ics). "Agenda" card on the Today page. Quick Add: `event: dentist fri 5pm for 30 min` (rules only; the model has no calendar action yet).
- **Tests:** 248 Kotlin tests, 0 skipped (models present).
- **Live check (built distributable, 5 Oct):** your real database migrated 12 → 13 (`integrity_check` ok, new columns present); the app started with no errors in `pebble.log`. Computer use was not available in the session, so the page was checked as rendered images (dark and light) of the real `CalendarContent` and Agenda card. **Please click through the Calendar page once yourself:** add an event, Skip day on a repeating one, import an .ics from Google Calendar.
- **Defaults to review:** an event lasts 1 hour; a timed event reminds 15 min before (also imported ones: the import uses the reminder chip on the page; pick "No reminder" first to import without); all-day events have no reminder.
- **Follow-ups:** edit an event (today: delete and add again); change one occurrence other than "skip"; `calendar_set` from the command model (needs a new eval file and gate).

### 5 Oct: WP C3 done (PR #29, on top of #28)
- **Formula review (Opus) → ADR 0010.** Measured on the eval sets, the plan's formula had 3 problems:
  - cosine ≥ 0.90 alone let one example reach 3.5 sentences on average (up to 13);
  - `confirmed` rows reinforced mistakes that you did not correct;
  - k = 3 was too slow for a taught phrase.
- **Fix:**
  - neighbours also need shared words (Jaccard ≥ 0.2), so one example reaches 0.5 sentences on average;
  - only taught, picked and wrong rows vote; the same sentence counts 4, k = 2;
  - a veto removes probability and does not give it to other actions;
  - the layer never lifts a removal above 0.85.
- **Replay gate** (`PersonalReplayTest`): right 14 → 27, wrong actions 12 → 4. Eval v1 does not change (68/68, 0 wrong actions).
- **UI:** a "Teach Pebble a command" card on the Memory page replaces the "AI assistants — coming later" placeholder (`TeachStateHolder`).
- **Data:** taught phrases are `command_feedback` rows with outcome `taught` (no migration). "Forget what you taught me" deletes them and sets `brain.personalSince`.
- **Not run locally:** the Python lint and test (`uv` is not on this machine's PATH). CI runs them.
- **Live check (built distributable, 5 Oct):**
  - "diary kholo" → "Can't do this yet";
  - teach it as "Show my notes" on the Memory page → "diary kholo" shows your notes;
  - "Forget what you taught me" → back to "Can't do this yet". Test data was removed afterwards.
  - The check found that the Teach card was squeezed under the Privacy card. It now shares the left column.
  - Your database is now at schema 11 (C2's `vector_item`). The installed v0.1.2 still starts on it.
- **Follow-up (not C3), fixed on 5 Oct (PR #41, then globally in ADR 0017):** `pebble.log` showed "history roll-up failed: [SQLITE_BUSY] database is locked" at start-up. Cause: a deferred transaction that reads, then writes after the event writer committed. See the "SQLITE_BUSY fix" entry above.

### 5 Oct: WP E1 done (sync-ready rows)
- Migration `11.sqm`: `note` and `one_off_reminder` gain `uid` (unique, backfilled), `updated_at`, `deleted_at`, `hlc`, `origin_device`. Deletes are tombstones, purged after 90 days. `device.id` is made at the first start (ADR 0013).
- **Live check:** your real database migrated 11 → 12 (`integrity_check` ok, all rows got uids and the device id). A ✕ on the Reminders page left a tombstone. Backup taken **after** the migration (my pre-migration backup command failed): `%APPDATA%\Pebble\pebble-backup-e1.db`. Delete it when you are happy.
- Test change: the v4 fixture in `MigrationTest` gained the `note` and `one_off_reminder` tables that every real v4 database has (11.sqm alters them). No assertion changed.
- **Model work (C6) is deferred by you** (RTX 4050 6 GB, no budget for now). The plan is in ADR 0012; do not start it until you bring it up.

### 5 Oct: WP C5 done (local chat, "Smart replies")
- **Model choice (ADR 0012):** four models measured on `brain/eval/chat_v1.jsonl` (24 lines in 3 scripts), and a person read every reply:
  - Qwen2.5-0.5B and Qwen3-0.6B: Hindi is not correct; one insult in Hindi.
  - Qwen2.5-1.5B: good English, weak Hinglish and Hindi. **Selected, English only.**
  - sarvam-30b (19.6 GB): no reply through llama.cpp b11146 (thinking cannot be turned off; the input looked corrupted). A candidate for the hub.
  - The rejected models were deleted from this PC (about 22 GB). Only `brain/models/chat` (Qwen2.5-1.5B + llama-server b11146, 1.1 GB) stays.
- **Safety review:** low mood, self-harm, health, law and money lines never reach the model; `ChatSafety.clean` refuses claimed actions, links, insults, the wrong script and echoes. The first eval had found medical advice ("paracetamol or ibuprofen?").
- **Live check (built app):**
  - the chat pack installs from About;
  - Smart replies on → "how was your day?" gets a model reply in about 1 s;
  - the server listens only on 127.0.0.1; no key → 401; no web UI;
  - the helper uses 1.7 GB RAM;
  - a server left by a hard kill is stopped at the next start.
  - Found and fixed in the check: the About card had no chat row; the Privacy card cut off its last switch (it now scrolls); a "Just chatting" pick skipped Smart replies.
  - Test data and the test-installed chat pack were removed.
- **Not published yet:** the chat pack. Sign it with `./gradlew :desktopApp:modelPack --args="sign chat qwen2.5-1.5b-q4km 0.2.0 pebble-2026a <key> brain/models/chat <out.zip>"` and attach it to the next release.
- **Next for Hindi/Hinglish chat:** distillation (C6). See ADR 0012, "Alternatives".

### 5 Oct: WP D2 done (signed model packs, lite installer)
- **Signing review (Opus) → ADR 0011:**
  - Ed25519 over the exact bytes of `pack.json`, with key ids, so a key can be changed.
  - The pack is bound to its model name.
  - Zip-slip and size checks.
  - The signature is checked again at each load.
  - Install and removal are staged and done at the next start.
- **Your signing key:** `C:\Users\Avik\.pebble\signing\pebble-2026a.key`.
  - **Make an offline backup now** (USB stick or password manager). If it is lost, no new pack can be signed for installed apps. Never commit it.
  - Public key `pebble-2026a` is compiled into `ModelPack.TRUSTED_KEYS`.
- **Live check (built app):**
  - a tampered pack is refused ("signature does not match");
  - the genuine signed command-model pack installs, applies at restart, and loads from `%APPDATA%\Pebble\models\intent`;
  - a file changed after install → "Pack not used", and the bundled model loads;
  - Remove + restart → back to "Built in";
  - the lite build has no speech models ("Not installed") and runs.
- **Release:** `release.yml` now builds `Pebble-x.y.z.msi` and `Pebble-lite-x.y.z.msi`. Packs are signed on your laptop: `./gradlew :desktopApp:modelPack --args="sign …"` (see CLAUDE.md). No pack is published yet. Publish a speech pack with the next release, for lite users.

### The PR stack is merged (5 Oct 2026)
All 17 work-package PRs (#16–#32) are squash-merged into `main` in order, one commit each (A1 … E1); #19 (the `reduce_range` model fix) is inside #18's commit. Dependabot #6 was closed on 5 Oct.

Lessons from the merge (for the next stack):
- **Deleting a PR's base branch closes the PR** (this repo deletes merged branches). Point the next PR at `main` *before* you merge the one under it.
- `main` requires up-to-date branches: after each squash-merge, `git rebase --onto main <old base commit> <branch>`, push, wait for CI.
- CI runners change CPU (AMD EPYC, Intel Xeon 8370C / 6973P-C). Old commits with a 1e-3 ONNX tolerance can fail on a new CPU; the tolerance fix (5e-3, `MAX_CONFIDENCE_DRIFT`, `MAX_EMBEDDING_DRIFT`) is now on `main`. One Spotless "could not read path" failure was a flake (re-run).
- Model releases: `models-2026.11` (v3) is the current manifest. The installed v0.1.2 still has the old model, so **cut a new app release** (0.3.0: needs your yes for `gh release`; publish the chat pack and a speech pack with it).

### Checks only you can do (the live app)
1. Reminders page: click +/−, a toggle, a strictness chip and ✕ once (B7; the synthetic clicks hit the IDE).
2. Memory page: try one search in "Search memory" (C2).
3. ~~A reminder toast appears (B3 binding).~~ Seen in the 5 Oct live check ("Call mom").
4. The older items below: battery run, "Start with Windows", noreply email, code signing.

### Open decisions
- **Search model (C2):** keep the shared encoder (14/16), ship e5-small for search (16/16, +~100 MB while loaded), or a contrastive fine-tune in C6.
- My two test turns ("open reminders", "notes kholo") are in your real Chat history. Delete them if you like.

### Next session
1. Say "continue from NEXT_SESSION.md" on `main`.
2. **E3c** (history and restore) is next: write its short spec and get approval first. Release 0.3.0 waits for your yes: publish the chat pack and a speech pack with it.
3. B7 roll-outs, one page per PR (Today, Notes, Water, Chat, rest of Memory, Companion), using `docs/UI-PATTERN.md`.

### Lessons from this session (for the implementer)
- Bash `${var/#pattern/...}`: a leading `#` in the pattern means "at the start". The PR-description edits that used it did nothing. Use `--body-file`.
- Heredocs that contain `'''` fail in this tool. Write the script to a file first.
- `runTest`'s `backgroundScope` is not run by `advanceUntilIdle()`. See `docs/UI-PATTERN.md`.

## Where things stand (end of 2 Oct 2026)

- **Released:** [v0.1.2](https://github.com/Wickedsoni/pebble/releases/tag/v0.1.2). The MSI has the models bundled; the user installed and tested it.
- **Models:** published as [models-2026.10](https://github.com/Wickedsoni/pebble/releases/tag/models-2026.10). Get them with `python brain/src/pebble_brain/download_models.py` (checks SHA-256 against `brain/models/manifest.json`).
- **How work lands:** `main` is protected, so every change goes branch → PR → CI (ktlint/Spotless, Ruff, all tests) → squash-merge. See CONTRIBUTING.md.
- **Releasing:**
  1. No version bump in the code: the tag sets the version (`release.yml` passes `-PappVersion`; the tag must be `vX.Y.Z`). The default in `desktopApp/build.gradle.kts` (`appVersion`) is for local builds.
  2. Write `docs/releases/vX.Y.Z.md`.
  3. Tag `vX.Y.Z` on main. `release.yml` builds the MSI on GitHub and publishes it (pre-release below 1.0).

### What's in the app
- **Pet:**
  - 5 characters with moods
  - hides over fullscreen windows; quiet while a video app or site is in front
  - growth stages earned through tasks (`shared/.../growth/Growth.kt`)
- **Talk or type** (Ctrl+Alt+Space; hold to talk):
  - Quick Add is a conversation view; every exchange is saved and shown on the Chat page
  - voice commands run automatically when Pebble is confident; "Not what I meant" undoes
- **Brain:**
  - **Command model:** intent-v2 (intent + slots + mood, int8 ONNX, ~5 ms), calibrated `DecisionPolicy`.
  - **Speech cascade:** Dolphin-base CTC for Hindi/Hinglish, Whisper-base for English.
  - **Speech accuracy:** FLEURS Hindi 16% CER clean at RTF 0.015, vs Whisper-small 30% at RTF 0.8. No denoiser: it made recognition worse.
- **Reminders:**
  - calm defaults: eyes and water 60 min, stretch 90
  - back-off on skip, "Less often", gentle reminders step aside after 3 minutes
  - nudge-timing bandit; rotating copy in the user's language
- **Privacy:**
  - microphone switch (Memory → Privacy)
  - opt-in voice clips and watch history
  - About page + PRIVACY.md
- **Tests:**
  - 90 Kotlin tests, 2 Python tests
  - `tools/test/ui-smoke.ps1` (drives the installed app)
  - `tools/perf/measure.ps1` (CPU / memory / battery)

### Measured on the installed build
- **Idle CPU:** 0.02%.
- **Memory:**

  | State | Memory |
  |---|---|
  | Pet only | 138 MB |
  | Command model loaded | 266 MB |
  | Speech models loaded | 578 MB |
  | After the 10-minute idle unload | 233 MB |

- **Battery:** not measured yet; the laptop was plugged in.

## Open items
- **Untested on real hardware:**
  - "Start with Windows"
  - a battery drain run (`measure.ps1` unplugged, with and without Pebble)
- **Commit author email is public in history.** Consider `git config user.email <id>+Wickedsoni@users.noreply.github.com` for future commits.
- **Small talk is canned lines** (varied, never repeated back-to-back). Real conversation needs M5 (local LLM).
- **Your own voice test set:** record it with `./gradlew :desktopApp:recordVoiceEval`, then score it with `VoiceCommandEvalTest`.

## Master plan (v0.2 → v1.0)
- **Plan:** `C:\Users\Avik\.claude\plans\lets-improve-the-current-fuzzy-puffin.md`, split into work packages (WPs).
- **Rules for implementers:** `CLAUDE.md` (repo root).
- **Done:**
  - WP A1: `CLAUDE.md`, `docs/STYLE-STE.md`, `docs/GLOSSARY.md` and ADRs 0000-0009 in `docs/adr/`.
  - WP A2: stale lines fixed in `README.md`, `docs/ARCHITECTURE.md` and `PetModel.kt`; M8-M10 and the network principle added to `docs/ROADMAP.md`.
  - WP A3: CI downloads the models and builds the distributable, so the model tests run on each PR. Added `SchemaParityTest`, Kover 0.9.11 (report only) and `allWarningsAsErrors`.
  - WP B1: `AppEnv` (clock, zone, dispatchers) injected into `PebbleApp`; one `appScope` for every coroutine; `ResolveTimeTest` (9 cases on a fixed clock).
  - WP B2: `CommandExecutor` returns `Executed(line, undo)`; `lastUndo` is gone; voice clips moved to `VoiceCorrectionService`; `CommandExecutorTest`.
  - WP B3: `UiPort` replaces the `notifier` / `openPage` vars (bound once in `Main`); `Logger` + `FileLogger` (`pebble.log`); quiet `runCatching` failures in `PebbleApp` and `VoiceCorrectionService` are logged.
  - Fix (in the B3 PR): "open reminders" / "notes kholo" / "रिमाइंडर खोलो" open that page (a rule before the model). Before, the model read "reminder" and asked "When should I remind you: “Open”?". Checked in the running app.
  - WP B4: `LazyModel<T>` + `ModelStatus`, `ModelRuntime` (one place for ONNX-before-sherpa), `VerifiedModelCache`, outputs read by name, no boxing in the speech trim. First voice command after an idle unload: 1.33-1.60 s -> 1.12-1.21 s (speech checksum check ~180 ms -> ~1 ms).
  - WP B5: `EventLogger` queues events and one IO writer saves them in batches (nothing lost: full queue or exit writes at once); `shutdown()` waits up to 2 s for the queue. File databases use WAL + `busy_timeout=5000`.
  - WP B6: measured 200 000 log entries (`PEBBLE_BENCH=1 ./gradlew :shared:jvmTest --tests "*GrowthBenchmarkTest*"`): `stats()` 88 ms (158 ms with the decoded check) → 31 ms after the roll-up; `learn()` ~85 ms. Migration `9.sqm` (`daily_stat`); `HistoryCompactor` keeps raw rows (your choice, ADR 0004 revised).
  - WP B7 (pilot): `RemindersStateHolder` (`StateFlow<RemindersUiState>`, `onEvent`), stateless `RemindersContent`, `docs/UI-PATTERN.md` (the template for the other pages).
  - WP C1: command model `intent-v3-pruned` (release `models-2026.11`) adds the sentence embedding (384, unit length) as a 4th output; same weights, other outputs identical; `Understood.embedding`.
  - WP C2: memory search (notes, facts, what you said) — `vector_item` (migration `10.sqm`), `MemorySearch`, "Search memory" on the Memory page, "what did I note about X" → search. 14/16 on the frozen search eval.
  - WP E1: sync-ready rows (`uid`, tombstones, `device.id`; migration `11.sqm`; ADR 0013).
  - WP C5: local chat ("Smart replies", off by default): `LocalChat` (llama-server b11146 on 127.0.0.1), `ChatSafety`, Qwen2.5-1.5B English only; ADR 0012.
  - WP D2: signed model packs (`ModelPack`, Ed25519, ADR 0011): the user folder loads only valid signed packs; "Model packs" card on the About page; `-Pflavor=lite`; the release builds both MSIs.
  - WP E3b (PR #47): merge (`changesSince`, `apply`), journal epoch on restore, `ConvergenceTest` gate.
  - WP E3a (PR #46): change journal + HLC, guard triggers, reconcile for older Pebble versions; a delete always wins (ADR 0018).
  - WP C4 (PR #39): offline IPS evaluator and gate for nudge policy changes (`NudgeIpsEvaluator`, `nudgeIps`; ADR 0016).
  - WP E4 (PR #37): encrypted backup/restore (`BackupFile`, `Backup`, About → Backup; ADR 0015).
  - WP E2: calendar (`calendar_event`, migration `12.sqm`, RRULE subset, ICS import/export, Calendar page, Agenda card, `event:` syntax; ADR 0014).
  - WP C3: `PersonalLayer` (Tier 2): taught phrases, picks and "Not what I meant" change the next reading at once; hour-of-day prior re-ranks "Did you mean…"; "Teach Pebble a command" card. Formula reviewed and changed (ADR 0010). Replay: right 14 → 27, wrong actions 12 → 4; eval v1 unchanged.
- **Coverage baseline (2026-10-04, local, with models):** 45.4% of lines, 32.2% of branches, both modules merged.
- **Kotlin compiler warnings:** 0. The build script has 1 Gradle deprecation warning (`compose.material3` in `desktopApp/build.gradle.kts`).
- **Model fix (from A3):** the command model `intent-v2-pruned` saturated on x86 CPUs without VNNI. `intent-v2r-pruned` uses `reduce_range=True`. Router eval 68/68 right or right-first-choice (was 67/68), 0 acted wrongly, mood 86.7%, 4.3 ms. It ships in the models release `models-2026.10b`.
- **Next:** E3c spec (history and restore); B7 page roll-outs one per PR (`docs/UI-PATTERN.md`); C6 distillation for Hindi/Hinglish chat.
- **Search quality decision (open):** memory search uses the command model's embedding + shared words: 14/16 on `memory_search_v1`; the two misses are English words for Hindi notes. The original e5-small scored 16/16 but is a second ~100 MB model. Options: keep as is; ship e5-small as a search model; or a contrastive fine-tune in C6.

### User-only tasks (do not automate)
1. The GitHub MCP server is disconnected (its token expired); Claude uses the `gh` CLI. Authorize it again if you want it.
2. Set a noreply commit email: `git config user.email <id>+Wickedsoni@users.noreply.github.com`.
3. Do a battery run with `tools/perf/measure.ps1`, unplugged, with and without Pebble.
4. Check "Start with Windows" on the installed app.
5. Select code signing: SignPath Foundation (free signing for open source) or Azure Trusted Signing.

## Next session — suggested order (before the master plan)
1. **Battery test + "Start with Windows" check** on the installed 0.1.2. Fix anything heavy.
2. **Wardrobe + custom SVG avatars + size slider** (design in `docs/ROADMAP.md`): layered renderer and size slider first.
3. **M5 local chat:** a small open LLM (e.g. Qwen2.5-0.5B via llama.cpp) for real replies; unloads when idle.
4. **Retrain with your feedback** once `command_feedback` has picks: `train_intent --feedback auto`, gated by `EvalSetRouterTest`.
5. **M2:** memory search + "teach Pebble a new command".

## Reference
- **Architecture:** `docs/ARCHITECTURE.md` · **roadmap:** `docs/ROADMAP.md` · **brain pipeline:** `brain/README.md`
- **Rebuild models from scratch:** see `brain/README.md` (train → prune_vocab → export_onnx → prepare_asr voice → package_models).
