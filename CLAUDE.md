# CLAUDE.md: rules for AI implementers

This file tells an AI model (or a person) how to work in this repo safely.
Read it before you change code. The master plan is outside the repo:
`C:\Users\Avik\.claude\plans\lets-improve-the-current-fuzzy-puffin.md`.
The plan splits the work into work packages (WPs), for example "A1" or "B3".

## How to resume

1. Read `NEXT_SESSION.md`. It shows the current status and the open items.
2. Find the next WP in the delivery order of the master plan.
3. Read the "Read first" list of that WP. Then start.

## Rules

1. **One WP = one branch `wp/<id>-<slug>` = one PR.**
   - Do not push to `main`. `main` is protected: PR → CI → squash-merge.
   - End each commit message with the attribution trailer of the session.
2. **Read every file in the "Read first" list of the WP before you edit.**
   - If a fact in the plan does not agree with the file, trust the file.
   - Make the smallest change that fits, and write the difference in the PR description.
   - If the difference changes the design, **stop and report**. Do not improvise.
3. **Do not add a dependency** unless the WP names it.
   - **SQLDelight updates need a manual check** (ADR 0017): `PebbleSqliteDriver` copies its `JdbcSqliteDriver` 2.4.0. Compare the copy with the new `JdbcSqliteDriver.kt` and `JdbcSqliteSchema.kt` first. `ConcurrentWriteTest` fails on purpose until you do.
   - First, find the exact coordinates and the latest stable version on Maven Central, PyPI or GitHub releases (use WebFetch).
   - Add it to `gradle/libs.versions.toml`.
   - Do not write a version from memory.
4. **Do not use an external API from memory.** This applies to ONNX Runtime, sherpa-onnx, BouncyCastle, llama.cpp flags and SQLDelight.
   - Read the source, the docs or the `--help` output of the pinned version first.
   - The plan marks these items with **VERIFY**.
5. **A refactor that keeps behaviour must keep all existing tests green.** Do not edit their assertions. If a test must change, give the reason in the PR.
6. **Definition of Done for every WP:**
   - `./gradlew spotlessApply`, then `./gradlew spotlessCheck :shared:jvmTest :desktopApp:test --console=plain` passes.
   - If the WP changes brain or router code, the model tests ran (not SKIPPED). Run `python brain/src/pebble_brain/download_models.py` first.
   - If the WP changes packaging, networking, crypto or native libraries: run `./gradlew :desktopApp:createDistributable` and start the built app one time (see trap 1).
   - The docs are up to date and written in STE (`docs/STYLE-STE.md`).
   - If the WP makes a design decision, add an ADR in `docs/adr/`.
   - `NEXT_SESSION.md` is up to date.
7. **Windows shell:** use `.\gradlew.bat` in PowerShell, or `./gradlew` in Git Bash.
8. **A change to `NudgePolicy` priors or rewards needs the offline gate** (ADR 0016). Run `./gradlew :desktopApp:nudgeIps` and put its output in the PR. It must show SNIPS ≥ the mean reward of what ran, and ESS ≥ 200. "NOT ENOUGH DATA" means: do not make the change yet.
9. **WPs marked `[Opus]` are design or security work.** Do not start them without an approved spec document.

## Build and test commands

| Task | Command |
|---|---|
| Start the app | `./gradlew :desktopApp:run` |
| Format Kotlin | `./gradlew spotlessApply` |
| Coverage report (both modules) | `./gradlew :desktopApp:koverHtmlReport` → `desktopApp/build/reports/kover/html/` |
| Check format and run all Kotlin tests | `./gradlew spotlessCheck :shared:jvmTest :desktopApp:test --console=plain` |
| Get the models (for model tests) | `python brain/src/pebble_brain/download_models.py` |
| Build the app as a folder | `./gradlew :desktopApp:createDistributable` |
| Build the installer | `./gradlew :desktopApp:packageMsi` |
| Python format and lint | `cd brain; uvx ruff format . ; uvx ruff check .` |
| Python tests | `cd brain; uv run python -m unittest tests/test_feedback.py` |
| Offline gate for a nudge policy change (copy of your database) | `./gradlew :desktopApp:nudgeIps` |
| Full sync convergence gate (300 sequences, about 100 s) | `PEBBLE_CONVERGENCE_SEQUENCES=300 ./gradlew :shared:jvmTest --tests "*ConvergenceTest*"` |
| Router eval with a new model | `$env:PEBBLE_EVAL_MODEL="models/<new>-pruned"; ./gradlew :desktopApp:test --tests "*EvalSetRouterTest*" --rerun` |

## Module map

| Module | What it contains |
|---|---|
| `:shared` | Kotlin Multiplatform, `jvm()` target only. Domain logic (brain rules, router, policies, reminders, growth, memory) and SQLDelight data. |
| `:desktopApp` | JVM Compose Desktop app: UI pages, Win32 integration, ONNX command model, speech (sherpa-onnx), packaging. |
| `brain/` | Python training pipeline (not a Gradle module): data, training, ONNX export, eval, model download. |
| `tools/` | Scripts: UI smoke test (`tools/test/ui-smoke.ps1`), performance (`tools/perf/measure.ps1`). |

There is no build-logic module. Add a new module only when a second consumer exists (see ADR 0008).

## Privacy invariant

Pebble keeps all data on the laptop. Pebble has no cloud models and no telemetry.
Any code that opens a socket must obey all of these rules:
- The setting is off by default.
- The same PR rewrites `PRIVACY.md`, `SECURITY.md` and `docs/ARCHITECTURE.md`.
- The same PR adds an entry to the "Connections" audit page (plan WP F7).

## Verified facts (checked 2026-10-04)

Line numbers can move. If a line does not match, search for the name.

| Fact | Location |
|---|---|
| Modules: `:shared` (KMP, `jvm()` only), `:desktopApp` (JVM Compose). No build-logic. | `settings.gradle.kts`, `shared/build.gradle.kts` |
| `PebbleApp` = object graph + façades; constructor `PebbleApp(db, env = AppEnv.system())`; tests call `PebbleApp(DatabaseFactory.inMemory())` and use `app.executeFromModel`, `app.notes`, `app.commandFeedback` | `desktopApp/.../PebbleApp.kt:47`, `desktopApp/src/test/.../NotWhatIMeantTest.kt:21-40` |
| Commands run in `CommandExecutor.execute(cmd): Executed(line, undo)` (exhaustive `when`, ADR 0001); `PebbleApp.execute` / `describe` are façades; `PebbleApp` implements `CommandActions` (`addNote`, `remember`, `logWater`). No shared undo field (WP B2). Voice clips: `VoiceCorrectionService` | `desktopApp/.../command/CommandExecutor.kt`, `voice/VoiceCorrectionService.kt` |
| One `appScope` (`SupervisorJob` + `env.dispatchers.main`); `ModelManager`, `SpeechRecognizer`, `VoiceInput` get `appScope + env.dispatchers.default`, so `shutdown()` cancels all (WP B1) | `PebbleApp.kt` |
| UI hooks: `UiPort` (`notify`, `openPage`); `app.ui` forwards to the port that `Main` binds once with `app.bindUi(...)` in a `LaunchedEffect` (a second bind is logged and ignored). Quiet failures go to `Logger` → `%APPDATA%\Pebble\pebble.log` (rotates at 1 MB to `pebble.log.1`); tests get `Logger.None` (WP B3) | `desktopApp/.../core/UiPort.kt`, `core/Logger.kt`, `Main.kt` |
| Time comes from `AppEnv` (`clock`, `zone`, `dispatchers`): `PebbleApp(db, env = AppEnv.system())`, `app.now()`, `env.today()`, `env.localNow()`. The top-level `now()` / `minuteOfDay()` are `@Deprecated` (warnings are errors, so do not call them) (WP B1) | `desktopApp/.../core/AppEnv.kt`, `PebbleApp.kt` |
| `EventLogger.attach(bus, scope, writerDispatcher)`: the bus subscriber (unconfined) only queues each event (`Channel`, 4096); one writer (`env.dispatchers.io.limitedParallelism(1)`) saves batches of up to 256 in one transaction. A full or closed queue writes at once, so nothing is lost. `flush()` waits for what is queued; `PebbleApp.shutdown()` publishes `AppStopping`, then `close(2 s)`, then cancels (WP B5) | `shared/.../event/EventLogger.kt`, `PebbleApp.kt` |
| `EventBus`: `MutableSharedFlow`, buffer 256, `DROP_OLDEST` | `shared/.../event/EventBus.kt` |
| `Understanding` is a synchronous `fun interface understand(text): Understood?`; `CommandRouter(model: () -> Understanding?, ...)`; the router is called on `Dispatchers.Default` | `shared/.../brain/Understanding.kt:65`, `CommandRouter.kt:15`, `desktopApp/.../quickadd/QuickAddWindow.kt:156,165` |
| `ModelManager` and `SpeechRecognizer` are thin wrappers over `LazyModel<T : AutoCloseable>` (locate → verify → load on `io` → idle unload; `status: StateFlow<ModelStatus>`; `getOrNull()` never blocks). Speech models are one `AsrEngines` holder. `ModelRuntime` owns both (WP B4) | `desktopApp/.../brain/LazyModel.kt`, `brain/ModelRuntime.kt` |
| `VerifiedModelCache` (`%APPDATA%\Pebble\models-verified.json`: path, size, mtime, sha256) skips re-hashing unchanged files; the speech check went from ~180 ms to ~1 ms. Only the `ModelRuntime` instances use it; `ModelManager(scope)` / `SpeechRecognizer(scope)` built directly still hash every load (WP B4) | `brain/VerifiedModelCache.kt`, `brain/ModelChecksums.kt` |
| **Load-order invariant:** ONNX Runtime env before sherpa `LibraryUtils.load()`, in one place: `ModelRuntime.loadSherpa()` (lazy, not at start-up) | `brain/ModelRuntime.kt`, `VoiceSpikeTest` |
| Intent ONNX outputs read by name (`intent_logits`, `slot_logits`, `mood_logits`; `OrtSession.Result.get(String): Optional<OnnxValue>`, checked in ORT 1.30), with index fallback. Since v3 (WP C1) a 4th output `embedding` [batch, 384] (mean-pooled before dropout, L2-normalised) fills `Understood.embedding: Embedding?` (content equality, `cosine()`); null for older models | `OnnxIntentModel.kt:73-94`, `brain/src/pebble_brain/export_onnx.py:48`, `intent_model.py:36-41` |
| Schema: `.sq` files + migrations `1.sqm`…`13.sqm`. The **current schema version is 14; the next migration file is `14.sqm`** (upgrades 14→15). The `.sq` `CREATE TABLE` must also show the final schema | `shared/src/commonMain/sqldelight/dev/pebble/db/` |
| Sync-ready rows (WP E1, ADR 0013): `note` and `one_off_reminder` keep the `INTEGER` id and have `uid` (unique), `updated_at`, `deleted_at`, `hlc` (empty until E3), `origin_device`. **Deletes are tombstones**: every read must add `deleted_at IS NULL`; the daily IO job purges tombstones older than 90 days. `device.id` (base32, 128 bits) is made at the first start by `DeviceIdentity`; `claim()` at each start fills missing uids/devices | `Reminders.sq`, `Wellness.sq`, `11.sqm`, `settings/DeviceIdentity.kt` |
| `ReminderEngine` keys one-off reminders by `Long` id: `oneOffKey(r.id)` | `shared/.../reminders/ReminderEngine.kt:61,91,195` |
| `DatabaseFactory.create(file)`: `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=true` (sqlite-jdbc 3.53.4.0 property names); `inMemory()` keeps the defaults (WP B5) | `shared/src/jvmMain/.../db/DatabaseFactory.kt` |
| Counts over all history go through `EventHistory` (`daily_stat` before the watermark `history.rolledUpUntil`, raw rows after). `HistoryCompactor` rolls whole days older than 7 days from the learning loop (IO); raw rows are **kept** (ADR 0004). The reminder action is decoded, not matched as text. 200 000 entries: `stats()` 158 ms → 31 ms (WP B6) | `shared/.../history/`, `growth/Growth.kt` |
| Migration test pattern: build the old schema with raw JDBC, set `PRAGMA user_version`, open with `DatabaseFactory.create(file)` | `shared/src/jvmTest/.../MigrationTest.kt` |
| jlink modules: `modules("java.sql", "jdk.unsupported", "jdk.crypto.ec")` (Ed25519 on JDK 21, checked in the built `runtime/release`); JVM `-Xmx256m`, SerialGC | `desktopApp/build.gradle.kts:106-124` |
| Models bundled from `brain/models/manifest.json` by `stageModel`; the downloader is stdlib-only Python | `desktopApp/build.gradle.kts:69-96`, `brain/src/pebble_brain/download_models.py` |
| CI: `ConvergenceTest` runs 60 sequences on a PR and 300 on a push to `main` or a manual "Run workflow" (`PEBBLE_CONVERGENCE_SEQUENCES`). Windows kotlin job (bash) downloads the models (cached by the hash of `manifest.json`), builds the distributable, runs all tests with Kover coverage; Python job runs lint + one test | `.github/workflows/ci.yml` |
| Release CI already downloads models: `python brain/src/pebble_brain/download_models.py` | `.github/workflows/release.yml` |
| Memory search (WP C2): `MemorySearch` in `shared/.../search/` indexes notes, facts and said turns into `vector_item` (float32 LE BLOB, `model_version`); rank = 0.6 cosine + 0.4 shared words, `MIN_SCORE` 0.40; hits are read back from the source tables (privacy). Indexing runs only while the command model is loaded (`model.embedIfLoaded`); `app.searchMemoryLoading` loads it for a search you asked for. Gate: `MemorySearchEvalTest` ≥ 14/16 on `brain/eval/memory_search_v1.jsonl` | `shared/.../search/`, `PebbleApp.kt` |
| Personal layer (WP C3, ADR 0010): `CommandRouter(..., personal = PersonalLayer)` calls `personal.adjust(text, u)` right after `understand` (in `route` and `ask`). Examples = `command_feedback` rows `taught`/`picked` (+1) and `wrong` (−1); `confirmed` rows never vote (they only feed the hour-of-day prior). Neighbour = cos ≥ 0.90 **and** word Jaccard ≥ 0.2. No renormalising after a veto; removals capped at 0.85. `personal.refresh()` runs in the indexing loop and after each teach. Gate: `PersonalReplayTest` (replay not worse, eval v1 unchanged) | `shared/.../brain/PersonalLayer.kt`, `desktopApp/.../app/pages/TeachStateHolder.kt` |
| Model packs (WP D2, ADR 0011): `%APPDATA%\Pebble\models\<name>` loads **only** if `ModelPack.verifyInstalled` passes (Ed25519 over the exact `pack.json` bytes, key ids in `ModelPack.TRUSTED_KEYS`, then every file hash); else the bundled model. Install/remove are staged (`<name>.staged`, `<name>.remove`) and done by `ModelRuntime` at start-up. Private key: `%USERPROFILE%\.pebble\signing\pebble-2026a.key` (never in the repo). Sign: `./gradlew :desktopApp:modelPack --args="sign <name> <version> <minApp|-> pebble-2026a <key> <model dir> <out.zip>"`. Lite installer: `-Pflavor=lite` (no `asr`) | `desktopApp/.../brain/ModelPack.kt`, `tools/ModelPackTool.kt`, `desktopApp/build.gradle.kts` |
| Local chat (WP C5, ADR 0012): `LocalChat` runs `llama-server` (llama.cpp **b11146** = stable v0.5.0, flags checked with `--help`) from the signed "chat" pack (or `PEBBLE_CHAT_DIR`): `--host 127.0.0.1`, free port, key in env `LLAMA_API_KEY`, `--offline --no-webui --reasoning off`; 6 s reply timeout, 10 min idle stop, leftover servers killed at start. Model Qwen2.5-1.5B-Instruct Q4_K_M, **English only** (`PebbleApp.CHAT_SCRIPTS`). Every reply passes `ChatSafety.clean`; low mood / self-harm / health / law / money lines never reach the model. Setting `chat.smartReplies` off by default. Eval: `ChatEvalTest` (`brain/models/chat`) | `shared/.../brain/ReplyGenerator.kt`, `desktopApp/.../brain/LocalChat.kt` |
| Calendar (WP E2, ADR 0014): `calendar_event` (uid PK, E1 sync columns, `rrule`, `exdates`, `remind_minutes`, `visibility`). Expansion and ICS are in `:shared` **jvmMain** (`java.time`; commonMain has no date library): `RecurrenceRule` (subset: DAILY/WEEKLY BYDAY/MONTHLY BYMONTHDAY, INTERVAL, UNTIL or COUNT), `RecurrenceExpander`, `Ics`, `CalendarAgenda`. Reminders before events are one-off reminders linked by `event_uid` + `occurrence_at`, made 2 days ahead by the 10-minute loop and by `agenda.eventChanged`. Quick Add: `event: <title> [day] [time] [for N min]` only; the model has no calendar action | `shared/.../calendar/`, `Calendar.sq`, `12.sqm`, `desktopApp/.../app/pages/CalendarStateHolder.kt` |
| Backup (WP E4, ADR 0015): `BackupFile` = 36-byte header (`PEBBLEBK`, version 1, iterations, salt, nonce prefix) + AES-256-GCM chunks of 1 MiB (nonce = prefix, index, last flag; header as AAD); key PBKDF2-HMAC-SHA256 600 000 (369 ms). `Backup.export` = `VACUUM INTO` temp + encrypt (temp always deleted); `stageRestore` checks and writes `pebble.db.restore`; `PebbleApp.create()` calls `Backup.applyStaged` **before** opening the DB (old files → `pebble.db.before-restore`; this device keeps its `device.id`). JDK crypto only, no new jlink module | `shared/src/jvmMain/.../backup/`, `app/pages/BackupStateHolder.kt` |
| Nudge offline eval (WP C4, ADR 0016): `NudgeIpsEvaluator` pairs raw `nudge_decided` rows (logged propensity) with the first `reminder_due` and the first `reminder_acted` of the key within 30 min (reward = `NudgePolicy.rewardFor`; no reaction = 0 only if a later entry shows the app ran 30 min). An app start/stop before the reaction skips the decision (the engine forgets it). Target = `NudgePolicy.asTarget()` (today's beliefs, fixed). Reference = mean reward of what ran. Maintainer data 5 Oct: 9 episodes, ESS 8 | `shared/.../brain/NudgeIpsEvaluator.kt`, `desktopApp/.../tools/NudgeIpsTool.kt` |
| Database driver (ADR 0017): `DatabaseFactory` opens every database with `PebbleSqliteDriver` = SQLDelight 2.4.0's `JdbcSqliteDriver` (one connection per thread, listeners, create/migrate from `user_version`) with `BEGIN IMMEDIATE TRANSACTION`. sqlite-jdbc `transaction_mode` has no effect under SQLDelight (it sends its own `BEGIN`) | `shared/src/jvmMain/.../db/PebbleSqliteDriver.kt`, `DatabaseFactory.kt` |
| Change journal (WP E3a, ADR 0018, spec `docs/specs/E3-CHANGE-JOURNAL.md`): synced fields are listed only in `SyncTable` (`note`, `one_off_reminder` without `event_uid`, `calendar_event`). A write = `ChangeJournal.record(table, uid, values, at)` (one HLC; wall = `at`; `last` = `max(hlc)` of the journal, so one clock per DB) then the row with `hlc` = that HLC, in one transaction. Guard triggers (`Journal.sq`, `13.sqm`) stop a write that sets a new `hlc` without entries, and any un-delete. Writes that keep `hlc` (an older Pebble) pass; `ChangeJournal.reconcile` at each start records them; tests call `verify()`. **A delete always wins**: `CalendarRepository.save` returns false for a deleted uid. Merge (E3b): `changesSince(cursor, limit)` (whole rows; `limit` counts entries) and `apply(batch, wall)` (all or nothing; checks names, types and drift first; winners written one HLC group at a time; a grave drops the change). `sync.journalEpoch` changes on restore. Gate: `ConvergenceTest` (3 devices; 60 sequences on a PR, the full 300 on `main` and on "Run workflow") | `shared/.../sync/`, `Journal.sq` |
| Settings keys live in `SettingsRepository.Keys` (string key-value) | `shared/.../settings/SettingsRepository.kt` |

## Known traps

1. **jlink module trap.** Tests run on a full JDK. The installed app runs on a trimmed runtime.
   - `java.net.http` (HttpClient), `jdk.httpserver`, `jdk.crypto.ec` and `java.naming` each need an entry in `modules(...)` in `desktopApp/build.gradle.kts`.
   - Ed25519 on JDK 21 is in `jdk.crypto.ec` (checked 2026-10-05, WP D2). **VERIFY** X25519/ECDHE for TLS when that work starts.
   - Always start the built distributable after such a change.
2. **ONNX before sherpa.** Load the ONNX Runtime env before sherpa-onnx. Only `ModelRuntime.loadSherpa()` loads sherpa; do not call `LibraryUtils.load()` anywhere else.
3. **Model tests skip silently without their data.** CI downloads the models, so `EvalSetRouterTest`, `RouterWithModelTest`, `OnnxParityTest` and `BundledModelTest` run there.
   - `VoiceSpikeTest` and `VoiceCommandEvalTest` still skip in CI. They need speech models in a local layout, or your own recordings.
   - `OnnxParityTest` uses `desktopApp/src/test/resources/parity/<model folder>.json` when the model folder has no `parity.json`. When you ship a new command model, copy its `parity.json` to that folder.
   - Locally, a test that prints `SKIPPED` proves nothing. Run the model download first.
4. **A new migration needs four changes** (and since WP E3a, a new write path to a synced table goes through `ChangeJournal.record`; a new synced column needs `SyncTable`, the triggers and `reconcile`):
   - a file `N.sqm`, where N = the current version;
   - the updated `CREATE TABLE` in the `.sq` file;
   - a new `MigrationTest` case;
   - `SchemaParityTest` must stay green. It migrates a version-1 database through every `.sqm` and compares it with a fresh one.
5. **Do not delete `event_log` rows.** The offline IPS eval needs the raw history (ADR 0004). To count over all history, use `EventHistory`, not `eventsOfTypeSince(type, 0)`: rows before the watermark are already in `daily_stat`, and a raw query from 0 is slow on long histories.
6. **`Understood` is a data class that equality checks use.** Do not add a raw `FloatArray` field: arrays compare by reference. Use a wrapper that uses `contentEquals`, like `Embedding`.
7. **Code that opens a socket** must obey the privacy invariant above.
8. **Spotless/ktlint 1.8.0 runs in CI.** Run `./gradlew spotlessApply` before you commit.
9. **Kotlin warnings are errors** (`allWarningsAsErrors` in both modules). Fix the warning. Do not suppress it without a reason in the PR.
10. **int8 results depend on the CPU.** x86 CPUs without VNNI (for example AMD Zen 3, older Intel laptops) saturate full 8-bit weights.
    - Quantize with `reduce_range=True` (`export_onnx.py`). Do not remove it.
    - A model with 8-bit weights passed on a VNNI laptop but read 3 of 194 commands differently on the CI runner (AMD EPYC 7763).
    - The CI log step "Runner CPU" shows which CPU ran the tests.
11. **The database runs in WAL mode.** Recent changes can be in `pebble.db-wal`, not in `pebble.db`. To copy the database (backup, export), use SQLite's backup (`VACUUM INTO` or the backup API), not a file copy. `event_log` rows appear a few milliseconds after `bus.publish`; call `eventLog.flush()` before you read your own event.
    - **Every transaction begins IMMEDIATE** (`PebbleSqliteDriver`, ADR 0017), so a transaction that reads first is safe while the event writer saves. SQLDelight 2.4.0's own driver sends a plain deferred `BEGIN TRANSACTION`, and in WAL that fails at once with `SQLITE_BUSY_SNAPSHOT` (`busy_timeout` does not help). Open databases only through `DatabaseFactory`, never with `JdbcSqliteDriver`. Test: `ConcurrentWriteTest`.
    - `DatabaseFactory.inMemory()` (tests) is a temp **file**, not `:memory:`: SQLDelight shares one connection across threads for `:memory:`, and the IO writer and roll-up then collide with the test thread ("cannot start a transaction within a transaction").

12. **SQLDelight 2.4.0 resolves only lower-case `new.` and `old.` in a trigger.** Upper-case `NEW.uid` fails with "No table found with name NEW", although SQLite accepts both. Generated row classes rename the columns `field` and `value` to `field_` and `value_`.

## Docs

- Write new and rewritten docs in ASD-STE100 Simplified Technical English. See `docs/STYLE-STE.md`.
- Use the terms in `docs/GLOSSARY.md`.
- Record design decisions as ADRs in `docs/adr/` (template: `docs/adr/0000-template.md`).
- UI pages follow `docs/UI-PATTERN.md` (state holder + stateless content; the Reminders page is the template).
- Code comments keep the style of the surrounding code.
