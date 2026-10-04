# Pebble architecture: from what you say to what Pebble does

Everything below runs on your laptop. Nothing you type or say leaves it.

## The flow

```
INPUT ─────────────────────────────────────────────────────────────────────────────────────
  typed   Quick Add (Ctrl+Alt+Space tap)                                   ─┐
  voice   hold Ctrl+Alt+Space, or 🎤 in Quick Add                            │
          MicCapture        16 kHz mono; mic open only while you talk        │
          AudioPrep         80 Hz high-pass + level to -20 dBFS              │
          Silero VAD        trims silence (Whisper invents words on silence) │
          Whisper (sherpa)  speech → text; non-English re-decoded as Hindi   │
          transcript lands in the text box, editable ────────────────────────┤
                                                                             ▼
UNDERSTAND  (one pass, ~5 ms on CPU)
  QuickAddParser    exact syntax first: "water every 45m", "note: …", "remind me … at 5pm"
  HinglishTime      times and days in 3 scripts: "kal shaam saade 5", "शुक्रवार", "aadhe ghante baad"
  intent model      e5-small encoder → intent head · slot head · mood head   (int8 ONNX, 31 MB)
  PersonalLayer     your taught phrases, picks and "Not what I meant" change the reading at once (ADR 0010)
DECIDE
  Understood.actions  intent probabilities summed per Pebble action (calibrated)
  DecisionPolicy      act / ask "Did you mean…" by what a mistake costs (delete 0.9 … chitchat 0.5)
  low mood            caring reply, unless a real command was understood (then it runs)
ACT + REPLY
  PebbleApp.execute   notes, reminders, water, queries; pet bubble in the script you used
  "Not what I meant"  on every model-chosen action: undo + ask again
BEHAVE  (its own loop)
  ReminderEngine + NudgePolicy  bandit decides now / +10 / +30 min for repeating reminders
LEARN
  command_feedback    picked · confirmed · wrong · taught           → PersonalLayer (at once), train_intent --feedback
  nudge_stats         Beta beliefs per context × arm                → learns live, on device
  event_log           everything, incl. NudgeDecided propensities   → MemoryEngine, offline eval
  voice corrections   (opt-in) audio + corrected text               → Whisper fine-tune (planned)
```

## Where each stage lives

| Stage | Code | Model / data |
|---|---|---|
| Mic, prep, VAD, Whisper | `desktopApp/.../voice/` (`MicCapture`, `AudioPrep`, `SpeechRecognizer`, `VoiceInput`) | `models/asr/` (Silero VAD, Whisper int8) |
| Rules | `shared/.../quickadd/QuickAddParser.kt`, `shared/.../brain/HinglishTime.kt` | — |
| Intent / slots / mood | `desktopApp/.../brain/OnnxIntentModel.kt`, `ModelManager.kt` | `models/intent/` (from `brain/models/manifest.json`) |
| Model packs | `desktopApp/.../brain/ModelPack.kt`, About page; `./gradlew :desktopApp:modelPack` (maintainer) | signed packs in `%APPDATA%\Pebble\models\<name>` (ADR 0011) |
| Personal layer | `shared/.../brain/PersonalLayer.kt`; "Teach Pebble a command" on the Memory page | `command_feedback` table |
| Decisions | `shared/.../brain/DecisionPolicy.kt`, `CommandRouter.kt`, `Understanding.kt` | temperatures in `labels.json` |
| Acting, undo | `desktopApp/.../PebbleApp.kt`, `quickadd/QuickAddWindow.kt` | SQLite (`%APPDATA%\Pebble\pebble.db`) |
| Nudge timing | `shared/.../brain/NudgePolicy.kt`, `reminders/ReminderEngine.kt` | `nudge_stats` table |
| Memory | `shared/.../memory/MemoryEngine.kt` | `event_log`, `memory` tables |
| Training | `brain/src/pebble_brain/` | `brain/models/`, `brain/eval/` |

## Noise: no denoiser, on purpose

Pebble doesn't run a neural noise remover before Whisper. We measured GTCRN on FLEURS Hindi with fan-like (pink) and chatter (babble) noise at 5 and 10 dB. It *raised* the error rate for both Whisper sizes, matching published findings that denoising artifacts hurt modern speech models trained on noisy audio.

Robustness instead comes from:
- **Capture:** an 80 Hz high-pass filter and level normalisation (`AudioPrep`).
- **Silero VAD:** Whisper never hears long silence or background-only stretches.
- **Your edits:** the transcript is editable before it runs.
- **Fine-tuning:** later, on opt-in corrected clips from real rooms.

The evaluator can still test a denoiser (`./gradlew :desktopApp:asrEval -Pdenoise=true`), so the decision can be revisited with new models.

## Performance rules

- Every model loads only when needed and frees itself after 10 idle minutes. When idle, Pebble uses no model memory and no CPU for models.
- Whisper starts loading the moment the talk key goes down, so loading overlaps your speech.
- Recognition runs once, on release, over speech-only audio.
- The command model's ONNX Runtime must load before sherpa-onnx's. In the reverse order, the command model binds to sherpa's runtime and its numbers drift (`VoiceSpikeTest`). `SpeechRecognizer.warmUp` enforces this.

## How models get better (and why it's safe)

1. **You use Pebble.** Every model-chosen action is logged as `confirmed`. "Not what I meant" marks it `wrong`, and your pick from "Did you mean" becomes `picked`.
2. **Retrain on the dev machine (GPU):** `train_intent --feedback auto` mixes your labels (picked 3×, confirmed 1×) with MASSIVE and Pebble templates.
3. **Prune, export, calibrate:** `prune_vocab` (from *all* training text), then `export_onnx` (which runs `calibrate`, temperature per head, and writes `parity.json`).
4. **Gates, which a new model must pass to ship:**
   - `EvalSetRouterTest`: ≥ 67/68 right or right-first-choice, 0 acted wrongly.
   - Mood eval ≥ 80%.
   - `OnnxParityTest`: Kotlin reads every sentence exactly like Python.
5. **Ship:** point `brain/models/manifest.json` at it. The installer bundles what the manifest names. A signed model pack in `%APPDATA%\Pebble\models\` overrides it (ADR 0011).

Eval sets are frozen and never trained on. Every generator drops sentences too close to them (`pebble_data` leak check).
Only the nudge policy learns live on your laptop. Deep models change only through the gated retrain.

## Privacy

- **Mic:** opens only while you hold the key or press the mic button, and is closed on release (`AudioPrepTest`).
- **Audio:** Pebble discards the audio after transcription. If you turn on "Keep voice clips I correct" (Memory → Privacy, off by default), Pebble keeps only the clips that you corrected, with your text. You can delete them on the Memory page.
- **No cloud:** no cloud models and no telemetry.
- **Connections:** none to the internet. One local connection, off by default: with "Smart replies" on, Pebble runs `llama-server` (llama.cpp b11146) as a child process on 127.0.0.1 with a random port and key (`desktopApp/.../brain/LocalChat.kt`, ADR 0012). It starts on the first chat line and stops after 10 idle minutes and on exit.
