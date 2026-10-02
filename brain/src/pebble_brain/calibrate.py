"""Calibration: make the model's confidence honest ("0.8 sure" ≈ right 80% of the time).

Run:  uv run python -m pebble_brain.calibrate models/intent-v1-pruned
(export_onnx.py runs this automatically after quantising.)

Temperature scaling (Guo et al. 2017): one scalar T divides the intent logits before softmax. It never
changes which intent wins, only how sure the model claims to be — exactly what Pebble's act / ask
decision depends on. Pebble decides on *actions* (alarm_set and calendar_set are both "remind"), so the
runtime sums intent probabilities per action and T is fitted on that action-level likelihood. T is fitted on the int8 ONNX model (what ships), on MASSIVE dev + Pebble dev,
never on the eval sets. Writes "temperature" into labels.json (the Kotlin runtime reads it there).

Reports ECE (expected calibration error, 15 bins): the average gap between confidence and accuracy.
"""

from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoTokenizer

from .intent_model import MOODS, Labels, encode_words
from .massive import load
from .pebble_data import generate
from .pebble_intents import to_pebble

ROOT = pathlib.Path(__file__).resolve().parents[2]


def ece(probs: np.ndarray, correct: np.ndarray, bins: int = 15) -> float:
    edges = np.linspace(0, 1, bins + 1)
    total = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (probs > lo) & (probs <= hi)
        if m.any():
            total += m.mean() * abs(correct[m].mean() - probs[m].mean())
    return float(total)


def logits_for(ckpt: pathlib.Path, sentences: list[list[str]], batch: int = 64, output: int = 0) -> np.ndarray:
    """One output of the int8 model for every sentence: 0 = intent logits, 2 = mood logits."""
    tok = AutoTokenizer.from_pretrained(ckpt / "tokenizer")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(ckpt / "intent.int8.onnx"), opts, providers=["CPUExecutionProvider"])
    out = []
    for i in range(0, len(sentences), batch):
        enc, _ = encode_words(tok, sentences[i:i + batch])
        outs = sess.run(None, {"input_ids": enc["input_ids"].numpy().astype(np.int64),
                               "attention_mask": enc["attention_mask"].numpy().astype(np.int64)})
        out.append(outs[output])
    return np.concatenate(out)


def fit_temperature(logits: np.ndarray, gold: np.ndarray, to_action: np.ndarray) -> float:
    """T minimising the NLL of the gold *action*; [to_action] is the intent → action 0/1 matrix."""
    z = torch.tensor(logits, dtype=torch.float64)
    m = torch.tensor(to_action, dtype=torch.float64)
    y = torch.tensor(gold)
    log_t = torch.zeros(1, dtype=torch.float64, requires_grad=True)  # optimise log T so T stays positive
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=200)

    def step():
        opt.zero_grad()
        p = (z / log_t.exp()).softmax(-1) @ m
        loss = torch.nn.functional.nll_loss(p.clamp_min(1e-12).log(), y)
        loss.backward()
        return loss

    opt.step(step)
    return float(log_t.detach().exp())


def softmax(z: np.ndarray) -> np.ndarray:
    e = np.exp(z - z.max(1, keepdims=True))
    return e / e.sum(1, keepdims=True)


def calibrate_mood(ckpt: pathlib.Path, report: dict) -> float | None:
    """Mood temperature on mood dev sentences + as many neutral MASSIVE dev commands. None if no mood head."""
    sess = ort.InferenceSession(str(ckpt / "intent.int8.onnx"), providers=["CPUExecutionProvider"])
    if len(sess.get_outputs()) < 3:
        return None
    mood_dev = [e for e in generate() if e.partition == "dev" and e.mood]
    neutral = [e for e in load() if e.partition == "dev" and e.intent not in ("general_quirky", "general_greet", "general_joke")]
    neutral = neutral[:: max(1, len(neutral) // len(mood_dev))][: len(mood_dev)]
    dev = mood_dev + neutral
    gold = np.array([MOODS.index(e.mood or "neutral") for e in dev])
    z = logits_for(ckpt, [e.tokens for e in dev], output=2)
    t = fit_temperature(z, gold, np.eye(len(MOODS)))
    p = softmax(z / t)
    right = p.argmax(1) == gold
    report["mood_temperature"] = round(t, 4)
    report["mood"] = {"n": len(dev), "acc": float(right.mean()), "ece_before": ece(softmax(z).max(1), right),
                      "ece_after": ece(p.max(1), right)}
    print(f"mood T = {t:.3f}  dev acc {right.mean():.1%}  ECE {report['mood']['ece_before']:.3f} -> {report['mood']['ece_after']:.3f}  (n={len(dev)})")
    return t


def calibrate(ckpt: pathlib.Path) -> dict:
    labels = Labels.load(ckpt / "labels.json")
    # Same grouping as Understood.actions in Kotlin: Pebble actions are summed, but every unsupported
    # ("other") intent is its own column — music and weather are different things, not one "other".
    def group(intent: str) -> str:
        return f"other:{intent}" if to_pebble(intent) == "other" else to_pebble(intent)
    actions = sorted({group(i) for i in labels.intents})
    to_action = np.zeros((len(labels.intents), len(actions)))
    for i, intent in enumerate(labels.intents):
        to_action[i, actions.index(group(intent))] = 1
    dev = [e for e in load() if e.partition == "dev"] + [e for e in generate() if e.partition == "dev"]
    logits = logits_for(ckpt, [e.tokens for e in dev])
    gold = np.array([actions.index(group(e.intent)) for e in dev])
    t = fit_temperature(logits, gold, to_action)

    report: dict = {"temperature": round(t, 4), "n": len(dev), "by_script": {}}
    print(f"temperature T = {t:.3f}  (fitted on {len(dev)} dev sentences; >1 means the model was overconfident)")
    print("ECE = average |confidence - accuracy|. 'old' = top intent's p as the action's confidence (v1 router).")
    print(f"{'':<10} {'n':>5} {'action acc':>10} {'ECE old':>8} {'ECE new':>8}")
    scripts = np.array([e.script for e in dev])
    for script in ["all", *sorted(set(scripts))]:
        m = np.ones(len(dev), bool) if script == "all" else scripts == script
        old = softmax(logits[m])
        old_right = (old.argmax(1)[:, None] == np.arange(len(labels.intents))).astype(float) @ to_action
        old_right = old_right.argmax(1) == gold[m]
        new = softmax(logits[m] / t) @ to_action
        right = new.argmax(1) == gold[m]
        row = {"n": int(m.sum()), "action_acc": float(right.mean()),
               "ece_old": ece(old.max(1), old_right), "ece_new": ece(new.max(1), right)}
        report["by_script"][script] = row
        print(f"{script:<10} {row['n']:>5} {row['action_acc']:>10.1%} {row['ece_old']:>8.3f} {row['ece_new']:>8.3f}")

    mood_t = calibrate_mood(ckpt, report)

    path = ckpt / "labels.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["temperature"] = round(t, 4)
    if mood_t is not None:
        data["moods"] = MOODS
        data["mood_temperature"] = round(mood_t, 4)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    (ckpt / "calibration.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    return report


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    calibrate(ROOT / (sys.argv[1] if len(sys.argv) > 1 else "models/intent-v1-pruned"))
