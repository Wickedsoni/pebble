# Pebble — status and next-session plan

To resume: open this repo and say "continue from NEXT_SESSION.md".
Public repo: https://github.com/Wickedsoni/pebble · latest release: **v0.1.2** (pre-release, 2 Oct 2026).

## Where things stand (end of 2 Oct 2026)

- **Released:** [v0.1.2](https://github.com/Wickedsoni/pebble/releases/tag/v0.1.2). The MSI has the models bundled; the user installed and tested it.
- **Models:** published as [models-2026.10](https://github.com/Wickedsoni/pebble/releases/tag/models-2026.10). Get them with `python brain/src/pebble_brain/download_models.py` (checks SHA-256 against `brain/models/manifest.json`).
- **How work lands:** `main` is protected, so every change goes branch → PR → CI (ktlint/Spotless, Ruff, all tests) → squash-merge. See CONTRIBUTING.md.
- **Releasing:**
  1. Bump `packageVersion` in `desktopApp/build.gradle.kts`.
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
- **Dependabot PR #6** (Gradle wrapper 9.4.1 → 9.8.0) is waiting for review.
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
- **Coverage baseline (2026-10-04, local, with models):** 45.4% of lines, 32.2% of branches, both modules merged.
- **Kotlin compiler warnings:** 0. The build script has 1 Gradle deprecation warning (`compose.material3` in `desktopApp/build.gradle.kts`).
- **Model fix (from A3):** the command model `intent-v2-pruned` saturated on x86 CPUs without VNNI. `intent-v2r-pruned` uses `reduce_range=True`. Router eval 68/68 right or right-first-choice (was 67/68), 0 acted wrongly, mood 86.7%, 4.3 ms. It ships in the models release `models-2026.10b`.
- **Next WP:** B6 (history scaling: measure, then roll up).

### User-only tasks (do not automate)
1. Merge Dependabot PR #6 (Gradle wrapper 9.4.1 → 9.8.0). The GitHub MCP server is disconnected: its token expired. Authorize it again, or use the `gh` CLI.
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
