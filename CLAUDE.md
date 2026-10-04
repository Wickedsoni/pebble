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
| `PebbleApp` = object graph + executor; constructor `PebbleApp(db: PebbleDatabase)`; tests call `PebbleApp(DatabaseFactory.inMemory())` and use `app.executeFromModel`, `app.notes`, `app.commandFeedback` | `desktopApp/.../PebbleApp.kt:47`, `desktopApp/src/test/.../NotWhatIMeantTest.kt:21-40` |
| Shared mutable undo: `private var lastUndo`, set in `execute`, captured in `executeFromModel` | `PebbleApp.kt:245-263, 266-336` |
| Ad-hoc scopes in field initialisers (`ModelManager`, `SpeechRecognizer`, `VoiceInput`) | `PebbleApp.kt:93,105,110-111` |
| Public `var` UI hooks `notifier`, `openPage`, `voiceDir` | `PebbleApp.kt:99,102,117` |
| Global `fun now()` and `LocalDateTime.now()` inside `resolve/describeWhen` (time that tests cannot control) | `PebbleApp.kt:373-390, 402` |
| `EventLogger` attached with `Dispatchers.Unconfined`, so DB writes happen on the publisher thread (often Swing); comment: "nothing is lost on exit" | `PebbleApp.kt:143-144`, `shared/.../event/EventLogger.kt` |
| `EventBus`: `MutableSharedFlow`, buffer 256, `DROP_OLDEST` | `shared/.../event/EventBus.kt` |
| `Understanding` is a synchronous `fun interface understand(text): Understood?`; `CommandRouter(model: () -> Understanding?, ...)`; the router is called on `Dispatchers.Default` | `shared/.../brain/Understanding.kt:65`, `CommandRouter.kt:15`, `desktopApp/.../quickadd/QuickAddWindow.kt:156,165` |
| `ModelManager` and `SpeechRecognizer` duplicate locate → verify → lazy load → idle unload → status | `desktopApp/.../brain/ModelManager.kt`, `voice/SpeechRecognizer.kt` |
| SHA-256 re-hash of every model file on **every** load (≈300 MB for speech after each idle unload) | `brain/ModelChecksums.kt:17-28` |
| **Load-order invariant:** ONNX Runtime env before sherpa `LibraryUtils.load()` | `SpeechRecognizer.kt:66-69`, `VoiceSpikeTest` |
| Intent ONNX outputs by index: `out[0]` intent, `out[1]` slots, `out[2]` mood. Export names: `intent_logits`, `slot_logits`, `mood_logits`. The mean-pooled vector exists in PyTorch but is **not exported** | `OnnxIntentModel.kt:73-94`, `brain/src/pebble_brain/export_onnx.py:48`, `intent_model.py:36-41` |
| Schema: `.sq` files + migrations `1.sqm`…`8.sqm`. The **current schema version is 9; the next migration file is `9.sqm`** (upgrades 9→10). The `.sq` `CREATE TABLE` must also show the final schema | `shared/src/commonMain/sqldelight/dev/pebble/db/` |
| `one_off_reminder.id INTEGER AUTOINCREMENT`; hard `DELETE`; no `updated_at` | `Reminders.sq:13-19,43-44` |
| `ReminderEngine` keys one-off reminders by `Long` id: `oneOffKey(r.id)` | `shared/.../reminders/ReminderEngine.kt:61,91,195` |
| `JdbcSqliteDriver(url, Properties(), Schema)`; no WAL, no busy timeout | `shared/src/jvmMain/.../db/DatabaseFactory.kt:16-17` |
| `GrowthEngine.stats()` loads the whole `event_log` per type and matches JSON by substring `"\"action\":\"DONE\""` | `shared/.../growth/Growth.kt:76-100` |
| Migration test pattern: build the old schema with raw JDBC, set `PRAGMA user_version`, open with `DatabaseFactory.create(file)` | `shared/src/jvmTest/.../MigrationTest.kt` |
| jlink modules: `modules("java.sql", "jdk.unsupported")`; JVM `-Xmx256m`, SerialGC | `desktopApp/build.gradle.kts:106-124` |
| Models bundled from `brain/models/manifest.json` by `stageModel`; the downloader is stdlib-only Python | `desktopApp/build.gradle.kts:69-96`, `brain/src/pebble_brain/download_models.py` |
| CI: Windows kotlin job (bash), model tests SKIP (no models); Python job runs lint + one test | `.github/workflows/ci.yml` |
| Release CI already downloads models: `python brain/src/pebble_brain/download_models.py` | `.github/workflows/release.yml` |
| Settings keys live in `SettingsRepository.Keys` (string key-value) | `shared/.../settings/SettingsRepository.kt` |

## Known traps

1. **jlink module trap.** Tests run on a full JDK. The installed app runs on a trimmed runtime.
   - `java.net.http` (HttpClient), `jdk.httpserver`, `jdk.crypto.ec` and `java.naming` each need an entry in `modules(...)` in `desktopApp/build.gradle.kts`.
   - **VERIFY** the module name for X25519/Ed25519/ECDHE on the pinned JDK.
   - Always start the built distributable after such a change.
2. **ONNX before sherpa.** Load the ONNX Runtime env before sherpa-onnx. A refactor of model loading must keep this order in **one** place.
3. **Model tests skip silently without models.** A green CI run proves nothing for brain changes until WP A3 is merged.
4. **A new migration needs four changes:**
   - a file `N.sqm`, where N = the current version;
   - the updated `CREATE TABLE` in the `.sq` file;
   - a new `MigrationTest` case;
   - an extended `SchemaParityTest` (from WP A3).
5. **Do not delete `event_log` rows without a roll-up.** Growth, `MemoryEngine` and the offline IPS eval all read the history.
6. **`Understood` is a data class that equality checks use.** Do not add a raw `FloatArray` field: arrays compare by reference. Use a wrapper that uses `contentEquals`.
7. **Code that opens a socket** must obey the privacy invariant above.
8. **Spotless/ktlint 1.8.0 runs in CI.** Run `./gradlew spotlessApply` before you commit.

## Docs

- Write new and rewritten docs in ASD-STE100 Simplified Technical English. See `docs/STYLE-STE.md`.
- Use the terms in `docs/GLOSSARY.md`.
- Record design decisions as ADRs in `docs/adr/` (template: `docs/adr/0000-template.md`).
- Code comments keep the style of the surrounding code.
