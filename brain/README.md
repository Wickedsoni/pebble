# Pebble brain

Training code for Pebble's **local** models. Nothing here runs inside the app: models are trained
here (on the RTX 4050), exported to ONNX / GGUF, and the Kotlin app runs them with ONNX Runtime,
whisper.cpp and llama.cpp. Users never need Python.

Design and roadmap: one shared multilingual encoder (the "common base") with tiny task heads,
a situation model, a reward-learning habit policy, and on-demand speech and chat specialists.

## Setup (once)

```powershell
cd brain
python -m pip install --user uv     # if uv isn't installed yet
python -m uv sync                   # Python 3.11 + PyTorch (CUDA 12.6) + ML libraries, in brain/.venv
python -m uv run python -m pebble_brain.env_check   # must end with READY
```

Open the notebooks in IntelliJ / VS Code and pick the `brain/.venv` interpreter, or run
`python -m uv run jupyter lab`.

## Milestones

| | Notebook / module | You learn |
|---|---|---|
| M0 | `notebooks/01_pytorch_basics.ipynb` | tensors, autograd, the training loop, overfitting |
| M0 | `notebooks/02_embeddings_common_base.ipynb` | embeddings, transfer learning, few-shot prototypes |
| M1 | command understanding (below) | real data, eval sets, multi-task heads, vocab pruning, ONNX export |

## M1 pipeline (command understanding)

```powershell
python -m uv run python data/download_massive.py                       # MASSIVE en-US + hi-IN, licence-checked
python -m uv run python -m pebble_brain.train_intent --out models/intent-v1   # ~10 min on the RTX 4050, then evaluates on eval v1
python -m uv run python -m pebble_brain.prune_vocab models/intent-v1 models/intent-v1-pruned
python -m uv run python -m pebble_brain.export_onnx models/intent-v1-pruned  # → intent.int8.onnx (~31 MB) + parity.json
# score the candidate through the real app router; ship only if it beats the gate in EvalSetRouterTest:
#   $env:PEBBLE_EVAL_MODEL="models/intent-v1-pruned"; ./gradlew :desktopApp:test --tests "*EvalSetRouterTest*" --rerun
# then point models/manifest.json at it (path + sha256); the installer bundles whatever the manifest names
```

Current model (`models/manifest.json`): MASSIVE intent accuracy en 87.9% / hi 85.9% / Roman 79.1%,
Pebble actions 95.7% (int8), ~6.5 ms per command on 4 CPU threads, 33 MB with tokenizer.

## Folders

- `src/pebble_brain/`: reusable training code (grows per milestone).
- `notebooks/`: guided, run-top-to-bottom learning steps.
- `data/`: download scripts and `licenses.json`. Raw data is gitignored.
- `eval/`: fixed test sets, **never** trained on.
- `models/`: exported artifacts (gitignored) plus `manifest.json`.

## Rules

1. Only permissively licensed data and models for anything shipped (see `data/licenses.json`).
2. Never train on outputs of proprietary models (Claude, GPT, Gemini).
3. Eval sets are frozen. A model only ships if it beats the current one on them.
