"""Frozen evaluation for the M1 command model. Never train on what's evaluated here.

Run:  uv run python -m pebble_brain.evaluate models/intent-v0 [v1|v0]

Reports, per script (en / hi_deva / hi_roman):
  * MASSIVE test: intent accuracy and slot span F1 (exact match of slot type + words)
  * Pebble commands (eval/pebble_commands_<set>.jsonl, default v1): action accuracy after mapping MASSIVE → Pebble,
    for actions the model can produce; Pebble-only actions are listed separately (rules / M2 prototypes)
Writes eval.json next to the checkpoint so models can be compared over time.
"""

from __future__ import annotations

import json
import pathlib
import sys
import time
from collections import defaultdict

from .intent_model import Predictor
from .massive import load
from .pebble_intents import MODEL_ACTIONS, PEBBLE_ONLY_ACTIONS, to_pebble

ROOT = pathlib.Path(__file__).resolve().parents[2]
# v1 is the frozen gate: messy, code-mixed phrasing, checked to share no sentence with v0 or MASSIVE.
# v0 (clean draft) is kept so old numbers stay comparable.
EVAL_SET = "v1"


def pebble_eval(name: str) -> pathlib.Path:
    return ROOT / "eval" / f"pebble_commands_{name}.jsonl"


def spans(tags: list[str]) -> set[tuple[str, int, int]]:
    out, start, kind = set(), None, None
    for i, t in enumerate(tags + ["O"]):
        if t.startswith("B-") or t == "O" or (t.startswith("I-") and t[2:] != kind):
            if kind is not None:
                out.add((kind, start, i))
            kind, start = (t[2:], i) if t.startswith(("B-", "I-")) else (None, None)
    return out


def evaluate(ckpt: pathlib.Path, eval_set: str = EVAL_SET) -> dict:
    pred = Predictor(ckpt)
    report: dict = {"checkpoint": str(ckpt), "eval_set": eval_set, "massive_test": {}, "pebble": {}}

    test = [e for e in load() if e.partition == "test"]
    by_script = defaultdict(list)
    for e in test:
        by_script[e.script].append(e)
    for script, exs in by_script.items():
        right, tp, fp, fn = 0, 0, 0, 0
        for i in range(0, len(exs), 128):
            chunk = exs[i:i + 128]
            for e, (intent, _, tags) in zip(chunk, pred.predict([x.tokens for x in chunk])):
                right += intent == e.intent
                gold, got = spans(e.tags), spans(tags)
                tp += len(gold & got); fp += len(got - gold); fn += len(gold - got)
        f1 = 2 * tp / max(1, 2 * tp + fp + fn)
        report["massive_test"][script] = {"n": len(exs), "intent_acc": right / len(exs), "slot_f1": f1}

    rows = [json.loads(l) for l in pebble_eval(eval_set).read_text(encoding="utf-8").splitlines() if l.strip()]
    results = pred.predict([r["text"].split() for r in rows])
    per = defaultdict(lambda: {"n": 0, "right": 0, "misses": []})
    pebble_only = []
    for r, (intent, p, _) in zip(rows, results):
        action = to_pebble(intent)
        if r["action"] in PEBBLE_ONLY_ACTIONS:
            pebble_only.append({"text": r["text"], "want": r["action"], "model_says": action})
            continue
        s = per[r["script"]]
        s["n"] += 1
        if action == r["action"]:
            s["right"] += 1
        else:
            s["misses"].append({"text": r["text"], "want": r["action"], "got": action, "massive": intent, "p": round(p, 2)})
    for script, s in per.items():
        report["pebble"][script] = {"n": s["n"], "action_acc": s["right"] / max(1, s["n"]), "misses": s["misses"]}
    report["pebble_only"] = pebble_only

    # Mood head on the frozen mood set (models from before the head have none).
    mood_path = ROOT / "eval" / "mood_v1.jsonl"
    if getattr(pred, "has_mood", False) and mood_path.exists():
        mrows = [json.loads(l) for l in mood_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        got = pred.moods([r["text"].split() for r in mrows])
        by = defaultdict(lambda: [0, 0])
        misses = []
        for r, (m, p) in zip(mrows, got):
            by[r["script"]][0] += m == r["mood"]
            by[r["script"]][1] += 1
            if m != r["mood"]:
                misses.append({"text": r["text"], "want": r["mood"], "got": m, "p": round(p, 2)})
        report["mood"] = {"acc": sum(v[0] for v in by.values()) / len(mrows),
                          "by_script": {k: v[0] / v[1] for k, v in by.items()}, "misses": misses}

    # Latency on CPU for a single command — the number that matters on an 8 GB, no-GPU laptop.
    cpu = Predictor(ckpt, device="cpu")
    import torch
    torch.set_num_threads(4)
    cpu.predict([["warm", "up"]])
    t0 = time.perf_counter()
    for r in rows[:30]:
        cpu.predict([r["text"].split()])
    report["cpu_ms_per_command_4_threads"] = (time.perf_counter() - t0) / 30 * 1000

    (ckpt / ("eval.json" if eval_set == "v0" else f"eval-{eval_set}.json")).write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    print_report(report)
    return report


def print_report(r: dict) -> None:
    print("\nMASSIVE test            intent acc   slot F1")
    for s, m in r["massive_test"].items():
        print(f"  {s:<10} (n={m['n']:<4})   {m['intent_acc']:>8.1%}   {m['slot_f1']:>7.1%}")
    print(f"\nPebble commands, eval set {r.get('eval_set', 'v0')} (actions the model covers: {', '.join(MODEL_ACTIONS)})")
    for s, m in r["pebble"].items():
        print(f"  {s:<10} {m['action_acc']:.0%} of {m['n']}")
        for miss in m["misses"]:
            print(f"     x {miss['text']!r} -> {miss['got']} ({miss['massive']}, {miss['p']}) want {miss['want']}")
    print("\nPebble-only actions (handled by rules today, prototypes in M2):")
    for x in r["pebble_only"]:
        print(f"     {x['want']:<14} {x['text']!r} -> model says {x['model_says']}")
    if "mood" in r:
        m = r["mood"]
        print(f"\nMood head (eval/mood_v1): {m['acc']:.0%}  " + "  ".join(f"{k} {v:.0%}" for k, v in m["by_script"].items()))
        for x in m["misses"]:
            print(f"     x {x['text']!r} -> {x['got']} ({x['p']}) want {x['want']}")
    print(f"\nCPU latency: {r['cpu_ms_per_command_4_threads']:.1f} ms per command (4 threads, unquantised PyTorch)")


if __name__ == "__main__":
    evaluate(ROOT / (sys.argv[1] if len(sys.argv) > 1 else "models/intent-v0"), sys.argv[2] if len(sys.argv) > 2 else EVAL_SET)
