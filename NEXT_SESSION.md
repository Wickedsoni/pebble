# Pebble — status and next-session plan

To resume: open this repo and say "continue from NEXT_SESSION.md".
Public repo: https://github.com/Wickedsoni/pebble · latest release: **v0.1.2** (pre-release, 2 Oct 2026).

## Session handoff (5 Oct 2026) — read this first

**This file is current only on branch `wp/d2-model-packs`** (the top of the PR stack). `main` still has the 2 Oct version until the stack is merged.

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
- **Follow-up (not C3):** `pebble.log` shows "history roll-up failed: [SQLITE_BUSY] database is locked" once at start-up (also on 4 Oct). It is probably a deferred transaction that reads, then writes while the event writer commits (WAL returns BUSY at once, so `busy_timeout` does not help). It tries again 10 minutes later. Fix: begin the roll-up transaction as IMMEDIATE.

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

### The PR stack (nothing is merged yet)
Each PR is based on the one before it. Merge in this order, squash-merge each, and let GitHub retarget the next PR to `main`:

| Order | PR | Branch | What |
|---|---|---|---|
| 1 | #16 | `wp/a1-guardrails` | A1: `CLAUDE.md`, STE style, glossary, ADRs 0000-0009 |
| 2 | #17 | `wp/a2-hygiene` | A2: stale docs, roadmap M8-M10 |
| 3 | #19 | `model/intent-reduce-range` | Model fix: `reduce_range` (CPUs without VNNI). **Base is A3's branch:** merge #19 into `wp/a3-quality-gates` first |
| 4 | #18 | `wp/a3-quality-gates` | A3: model tests in CI, schema parity, Kover, warnings as errors |
| 5 | #20 | `wp/b1-time-dispatchers-scopes` | B1: `AppEnv`, one app scope |
| 6 | #21 | `wp/b2-command-executor` | B2: `CommandExecutor`, undo as a value |
| 7 | #22 | `wp/b3-ui-port-logger` | B3: `UiPort`, `Logger`; "open reminders" command |
| 8 | #23 | `wp/b4-model-runtime` | B4: `LazyModel`, `ModelRuntime`, checksum cache |
| 9 | #24 | `wp/b5-event-writes-sqlite` | B5: event writes off the UI thread, WAL |
| 10 | #25 | `wp/b6-history-scaling` | B6: `daily_stat` roll-up, raw rows kept |
| 11 | #26 | `wp/b7-ui-state-pilot` | B7 pilot: Reminders state holder, `docs/UI-PATTERN.md` |
| 12 | #27 | `wp/c1-sentence-embedding` | C1: sentence embedding (model `intent-v3-pruned`) |
| 13 | #28 | `wp/c2-memory-search` | C2: memory search |
| 14 | #29 | `wp/c3-personal-layer` | C3: personal layer + "Teach Pebble a command" (ADR 0010) |
| 15 | #30 | `wp/d2-model-packs` | D2: signed model packs, lite installer (ADR 0011) |

- Merge #25 and #26 close together: #26 has the fix for a slow-disk test timeout that #25's CI can hit.
- If a later PR shows conflicts after a squash-merge, rebase it on `main`. The content is the same.
- Model releases published this session: `models-2026.10b` (v2r), `models-2026.11` (v3, current in the manifest). The installed v0.1.2 still has the old model, so cut a new app release after the merge.

### Checks only you can do (the live app)
1. Reminders page: click +/−, a toggle, a strictness chip and ✕ once (B7; the synthetic clicks hit the IDE).
2. Memory page: try one search in "Search memory" (C2).
3. ~~A reminder toast appears (B3 binding).~~ Seen in the 5 Oct live check ("Call mom").
4. The older items below: battery run, "Start with Windows", Dependabot #6, noreply email, code signing.

### Open decisions
- **Search model (C2):** keep the shared encoder (14/16), ship e5-small for search (16/16, +~100 MB while loaded), or a contrastive fine-tune in C6.
- My two test turns ("open reminders", "notes kholo") are in your real Chat history. Delete them if you like.

### Next session
1. Say "continue from NEXT_SESSION.md" and check out `wp/d2-model-packs` (or `main`, if you merged the stack).
2. **C5** (local chat via a llama.cpp sidecar; Opus reviews the prompt and safety) is next in the delivery order. It opens a loopback socket: obey the privacy invariant (setting off by default, PRIVACY/SECURITY/ARCHITECTURE in the same PR).
3. B7 roll-outs, one page per PR (Today, Notes, Water, Chat, rest of Memory, Companion), using `docs/UI-PATTERN.md`.
4. Then C4 (offline IPS evaluator), D2, C5, as in the delivery order of the plan.

### Lessons from this session (for the implementer)
- Bash `${var/#pattern/...}`: a leading `#` in the pattern means "at the start". The PR-description edits that used it did nothing. Use `--body-file`.
- Heredocs that contain `'''` fail in this tool. Write the script to a file first.
- `runTest`'s `backgroundScope` is not run by `advanceUntilIdle()`. See `docs/UI-PATTERN.md`.

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
  - WP B6: measured 200 000 log entries (`PEBBLE_BENCH=1 ./gradlew :shared:jvmTest --tests "*GrowthBenchmarkTest*"`): `stats()` 88 ms (158 ms with the decoded check) → 31 ms after the roll-up; `learn()` ~85 ms. Migration `9.sqm` (`daily_stat`); `HistoryCompactor` keeps raw rows (your choice, ADR 0004 revised).
  - WP B7 (pilot): `RemindersStateHolder` (`StateFlow<RemindersUiState>`, `onEvent`), stateless `RemindersContent`, `docs/UI-PATTERN.md` (the template for the other pages).
  - WP C1: command model `intent-v3-pruned` (release `models-2026.11`) adds the sentence embedding (384, unit length) as a 4th output; same weights, other outputs identical; `Understood.embedding`.
  - WP C2: memory search (notes, facts, what you said) — `vector_item` (migration `10.sqm`), `MemorySearch`, "Search memory" on the Memory page, "what did I note about X" → search. 14/16 on the frozen search eval.
  - WP D2: signed model packs (`ModelPack`, Ed25519, ADR 0011): the user folder loads only valid signed packs; "Model packs" card on the About page; `-Pflavor=lite`; the release builds both MSIs.
  - WP C3: `PersonalLayer` (Tier 2): taught phrases, picks and "Not what I meant" change the next reading at once; hour-of-day prior re-ranks "Did you mean…"; "Teach Pebble a command" card. Formula reviewed and changed (ADR 0010). Replay: right 14 → 27, wrong actions 12 → 4; eval v1 unchanged.
- **Coverage baseline (2026-10-04, local, with models):** 45.4% of lines, 32.2% of branches, both modules merged.
- **Kotlin compiler warnings:** 0. The build script has 1 Gradle deprecation warning (`compose.material3` in `desktopApp/build.gradle.kts`).
- **Model fix (from A3):** the command model `intent-v2-pruned` saturated on x86 CPUs without VNNI. `intent-v2r-pruned` uses `reduce_range=True`. Router eval 68/68 right or right-first-choice (was 67/68), 0 acted wrongly, mood 86.7%, 4.3 ms. It ships in the models release `models-2026.10b`.
- **Next:** C5 (local chat), then E1; B7 page roll-outs one per PR (`docs/UI-PATTERN.md`).
- **Search quality decision (open):** memory search uses the command model's embedding + shared words: 14/16 on `memory_search_v1`; the two misses are English words for Hindi notes. The original e5-small scored 16/16 but is a second ~100 MB model. Options: keep as is; ship e5-small as a search model; or a contrastive fine-tune in C6.

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
