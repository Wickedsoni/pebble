# Roadmap

Pebble's brain is built as **one common base with small specialists, run as a cascade**:
- cheap rules first
- then a shared multilingual encoder with tiny task heads
- bigger models only on demand

Everything runs on the user's laptop. The minimum target is **8 GB RAM, an i5, no GPU**. Deep training happens on a developer GPU, and the results ship as versioned, gated model updates.

## Design principles

- **Local first.** No cloud models, no telemetry. Cloud AI agents are only ever an explicit, approval-gated user action.
- **Permissive licences only** for anything we train on or ship (see `brain/data/licenses.json`). No training on outputs of proprietary models.
- **Mirror the user's language:** Hinglish → Hinglish, Devanagari → Devanagari, English → English.
- **When unsure, ask.** "Did you mean…" picks and "Not what I meant" become training labels (active learning).
- **Push-to-talk only.** The mic is never open in the background.
- **Every model change is gated** by frozen eval sets that are never trained on.

## Milestones

| | Milestone | Status |
|---|---|---|
| M0 | **Foundations:** `brain/` (uv, PyTorch), eval harness, licence manifest | ✅ done |
| M1 | **Command understanding:** e5-small + intent/slot heads, int8 ONNX (31 MB, ~5 ms), rules cascade, "Did you mean", Hinglish times and weekdays | ✅ done (intent-v2: 65/68 on eval v1, 0 wrong actions) |
| M1+ | **Calibrated decisions + learning loop:** temperature scaling, cost-aware `DecisionPolicy`, "Not what I meant", feedback → retrain | ✅ done |
| M2 | **Memory search + teach a command:** embeddings in SQLite; few-shot prototypes ("teach Pebble a new command from 3–5 examples") | planned |
| M3 | **Habit brain (RL):** contextual Thompson-sampling bandit for nudge timing; offline evaluation with logged propensities (IPS) | 🟡 bandit live; offline IPS evaluation planned |
| M4 | **Speech:** push-to-talk, Silero VAD, Whisper via sherpa-onnx; voice eval set; opt-in correction clips → Whisper LoRA on Hinglish | 🟡 pipeline built; model choice and LoRA pending |
| M5 | **Local chat:** a small open LLM (e.g. Qwen2.5-0.5B, Apache-2.0) via llama.cpp with persona + retrieved memories; unloads when idle | planned |
| M6 | **Situation, mood and context:** self-supervised situation model over the event log; mood head (✅ in intent-v2) and context head feeding the bandit | planned |
| M7 | **Continual learning:** overnight on-device updates for heads, prototypes and bandit; dev-machine LoRA pipeline with replay, eval gates and rollback | planned |
| — | **Claude Code / agent hand-off:** the pet shows working / needs input / done; tasks run in a git worktree; nothing is pushed without review | planned |
| — | **Growth levels** earned by tasks (active days, reminders done, water goals, notes, chats) | ✅ done (0.1.2) |
| — | **Wardrobe + custom SVG avatars + size slider** (below) | planned |

## Next: your pet, your way (wardrobe + custom avatars)

A full "dress-up" system, built so the community can make and share pets.

**Layered pet**
- Every pet is drawn as layers:
  - **body**: a built-in character, or *your own SVG*
  - **slots**: head (cap, crown, flower), face (glasses), neck (scarf), hand (mug)
- Each layer is a vector, so it stays sharp at any size.
- Animation (bob, squash, hop, follow) is applied to the whole stack, so any body animates without extra work.

**Bring your own avatar (SVG)**
- **Import:** Companion → Wardrobe → *Import SVG*.
- **Safety check before anything is shown:**
  - at most 256 KB
  - a `viewBox` is required
  - scripts, external links and embedded images are stripped
- **Storage:** stored in `%APPDATA%\Pebble\avatars\<name>\`.
- **Optional conventions bring it to life:**
  - groups with `id="eye-left"` / `id="eye-right"` blink
  - `id="mouth"` changes with mood
  - anchor markers (`id="anchor-head"`, `anchor-face`, `anchor-neck`) tell accessories where to sit
- An SVG without them still works as a still figure that moves and bounces.

**Size and wardrobe**
- **Size slider:** 60–160 %. The pet window, its speech bubble and taskbar walking all scale with it.
- **Wardrobe:** pick per slot: cap on/off, glasses on/off, scarf colour… Items are SVGs too.
- **Unlocks:** some items come with growth levels (a crown at Legendary, sunglasses for a 7-day water streak), so the level tasks have visible rewards.

**Avatar packs**
- A zip with `pack.json` (name, author, licence, which files are body/accessories, anchors) + SVGs.
- Packs can be contributed to the repo under `avatars/` by PR, with a licence (CC0 / CC BY) required.

**Order:**
1. Layered renderer + size slider.
2. Wardrobe with built-in caps and glasses.
3. SVG import with sanitising.
4. Packs.

**Done when:**
- A hand-drawn SVG imported by a user blinks, wears a cap and walks at 140 % size.
- A malicious SVG (script, external URL, 50 MB) is rejected with a clear message.

## Budgets

- **Command understanding:** < 30 ms on a 4-thread CPU (today about 5 ms).
- **Speech:** < 1.5 s from key release to text for a 5 s clip.
- **Memory:** < 300 MB resident without the chat model, everything unloaded when idle, total model size on disk < 1.5 GB.

## Good places to help

- **Hinglish/Hindi test sentences and phrasings:** open a "Pebble misunderstood me" issue.
- **Speech:** recordings and evaluation for different accents and noise conditions (with consent, and permissively licensed).
- **M2 few-shot prototypes** (pure Kotlin, well-contained).
- **UI polish and accessibility** in `desktopApp/`.
