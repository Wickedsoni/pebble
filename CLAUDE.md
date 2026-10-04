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
8. **WPs marked `[Opus]` are design or security work.** Do not start them without an approved spec document.

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
| Intent ONNX outputs read by name (`intent_logits`, `slot_logits`, `mood_logits`; `OrtSession.Result.get(String): Optional<OnnxValue>`, checked in ORT 1.30), with index fallback. The mean-pooled vector exists in PyTorch but is **not exported** | `OnnxIntentModel.kt:73-94`, `brain/src/pebble_brain/export_onnx.py:48`, `intent_model.py:36-41` |
| Schema: `.sq` files + migrations `1.sqm`…`9.sqm`. The **current schema version is 10; the next migration file is `10.sqm`** (upgrades 10→11). The `.sq` `CREATE TABLE` must also show the final schema | `shared/src/commonMain/sqldelight/dev/pebble/db/` |
| `one_off_reminder.id INTEGER AUTOINCREMENT`; hard `DELETE`; no `updated_at` | `Reminders.sq:13-19,43-44` |
| `ReminderEngine` keys one-off reminders by `Long` id: `oneOffKey(r.id)` | `shared/.../reminders/ReminderEngine.kt:61,91,195` |
| `DatabaseFactory.create(file)`: `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=true` (sqlite-jdbc 3.53.4.0 property names); `inMemory()` keeps the defaults (WP B5) | `shared/src/jvmMain/.../db/DatabaseFactory.kt` |
| Counts over all history go through `EventHistory` (`daily_stat` before the watermark `history.rolledUpUntil`, raw rows after). `HistoryCompactor` rolls whole days older than 7 days from the learning loop (IO); raw rows are **kept** (ADR 0004). The reminder action is decoded, not matched as text. 200 000 entries: `stats()` 158 ms → 31 ms (WP B6) | `shared/.../history/`, `growth/Growth.kt` |
| Migration test pattern: build the old schema with raw JDBC, set `PRAGMA user_version`, open with `DatabaseFactory.create(file)` | `shared/src/jvmTest/.../MigrationTest.kt` |
| jlink modules: `modules("java.sql", "jdk.unsupported")`; JVM `-Xmx256m`, SerialGC | `desktopApp/build.gradle.kts:106-124` |
| Models bundled from `brain/models/manifest.json` by `stageModel`; the downloader is stdlib-only Python | `desktopApp/build.gradle.kts:69-96`, `brain/src/pebble_brain/download_models.py` |
| CI: Windows kotlin job (bash) downloads the models (cached by the hash of `manifest.json`), builds the distributable, runs all tests with Kover coverage; Python job runs lint + one test | `.github/workflows/ci.yml` |
| Release CI already downloads models: `python brain/src/pebble_brain/download_models.py` | `.github/workflows/release.yml` |
| Settings keys live in `SettingsRepository.Keys` (string key-value) | `shared/.../settings/SettingsRepository.kt` |

## Known traps

1. **jlink module trap.** Tests run on a full JDK. The installed app runs on a trimmed runtime.
   - `java.net.http` (HttpClient), `jdk.httpserver`, `jdk.crypto.ec` and `java.naming` each need an entry in `modules(...)` in `desktopApp/build.gradle.kts`.
   - **VERIFY** the module name for X25519/Ed25519/ECDHE on the pinned JDK.
   - Always start the built distributable after such a change.
2. **ONNX before sherpa.** Load the ONNX Runtime env before sherpa-onnx. Only `ModelRuntime.loadSherpa()` loads sherpa; do not call `LibraryUtils.load()` anywhere else.
3. **Model tests skip silently without their data.** CI downloads the models, so `EvalSetRouterTest`, `RouterWithModelTest`, `OnnxParityTest` and `BundledModelTest` run there.
   - `VoiceSpikeTest` and `VoiceCommandEvalTest` still skip in CI. They need speech models in a local layout, or your own recordings.
   - `OnnxParityTest` uses `desktopApp/src/test/resources/parity/<model folder>.json` when the model folder has no `parity.json`. When you ship a new command model, copy its `parity.json` to that folder.
   - Locally, a test that prints `SKIPPED` proves nothing. Run the model download first.
4. **A new migration needs four changes:**
   - a file `N.sqm`, where N = the current version;
   - the updated `CREATE TABLE` in the `.sq` file;
   - a new `MigrationTest` case;
   - `SchemaParityTest` must stay green. It migrates a version-1 database through every `.sqm` and compares it with a fresh one.
5. **Do not delete `event_log` rows.** The offline IPS eval needs the raw history (ADR 0004). To count over all history, use `EventHistory`, not `eventsOfTypeSince(type, 0)`: rows before the watermark are already in `daily_stat`, and a raw query from 0 is slow on long histories.
6. **`Understood` is a data class that equality checks use.** Do not add a raw `FloatArray` field: arrays compare by reference. Use a wrapper that uses `contentEquals`.
7. **Code that opens a socket** must obey the privacy invariant above.
8. **Spotless/ktlint 1.8.0 runs in CI.** Run `./gradlew spotlessApply` before you commit.
9. **Kotlin warnings are errors** (`allWarningsAsErrors` in both modules). Fix the warning. Do not suppress it without a reason in the PR.
10. **int8 results depend on the CPU.** x86 CPUs without VNNI (for example AMD Zen 3, older Intel laptops) saturate full 8-bit weights.
    - Quantize with `reduce_range=True` (`export_onnx.py`). Do not remove it.
    - A model with 8-bit weights passed on a VNNI laptop but read 3 of 194 commands differently on the CI runner (AMD EPYC 7763).
    - The CI log step "Runner CPU" shows which CPU ran the tests.
11. **The database runs in WAL mode.** Recent changes can be in `pebble.db-wal`, not in `pebble.db`. To copy the database (backup, export), use SQLite's backup (`VACUUM INTO` or the backup API), not a file copy. `event_log` rows appear a few milliseconds after `bus.publish`; call `eventLog.flush()` before you read your own event.

## Docs

- Write new and rewritten docs in ASD-STE100 Simplified Technical English. See `docs/STYLE-STE.md`.
- Use the terms in `docs/GLOSSARY.md`.
- Record design decisions as ADRs in `docs/adr/` (template: `docs/adr/0000-template.md`).
- Code comments keep the style of the surrounding code.
