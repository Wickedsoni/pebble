"""Scores speech recognition fairly across scripts (plan 3.4).

Run:  ./gradlew :desktopApp:asrEval        (writes models/asr/asr-hyps.jsonl, on the app's runtime)
      uv run python -m pebble_brain.asr_eval

Whisper often writes Hindi speech in Roman letters ("argentina duniya mein sabse achhi polo team")
while references are Devanagari. Plain WER then counts every word wrong. Pebble doesn't care which
script it gets (the command model reads both), so we compare *sounds*: both sides are romanised
(translit.romanize for Devanagari) and reduced to a consonant skeleton — vowels dropped, aspiration
and spelling variants merged (dunea/duniya → dn, achhi/acheap → c/cp) — then scored as:
  skeleton WER  word-level edit distance on skeletons (the closest to "did it hear the words")
  skeleton CER  character-level on the joined skeleton
Also reported: share of transcripts in Roman vs Devanagari.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
from collections import defaultdict

from .translit import has_devanagari, romanize

ROOT = pathlib.Path(__file__).resolve().parents[2]
HYPS = ROOT / "models" / "asr" / "asr-hyps.jsonl"

_MERGE = [
    ("ph", "f"),
    ("sh", "s"),
    ("kh", "k"),
    ("gh", "g"),
    ("ch", "c"),
    ("jh", "j"),
    ("th", "t"),
    ("dh", "d"),
    ("bh", "b"),
    ("w", "v"),
    ("z", "j"),
    ("q", "k"),
    ("x", "ks"),
    ("ck", "k"),
]


def skeleton_word(word: str) -> str:
    w = word.lower()
    for a, b in _MERGE:
        w = w.replace(a, b)
    w = re.sub(r"[aeiouhy]", "", w)  # vowels, leftover h and the y glide carry little across spellings
    return re.sub(r"(.)\1+", r"\1", w)  # doubled letters: acchi → aci


def skeleton(text: str) -> list[str]:
    words = text.split()
    if has_devanagari(text):
        words = romanize(words, seed=0)
    words = [re.sub(r"[^a-z]", "", w.lower()) for w in words]
    return [s for s in (skeleton_word(w) for w in words if w) if s]


def edits(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[-1]


def score(path: pathlib.Path = HYPS) -> dict:
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    agg: dict = defaultdict(lambda: {"we": 0, "wn": 0, "ce": 0, "cn": 0, "n": 0, "roman": 0})
    for r in rows:
        key = (r["model"], r["condition"], "+gtcrn" if r["denoise"] else "raw")
        ref, hyp = skeleton(r["ref"]), skeleton(r["hyp"])
        a = agg[key]
        a["we"] += edits(ref, hyp)
        a["wn"] += len(ref)
        a["ce"] += edits(list("".join(ref)), list("".join(hyp)))
        a["cn"] += len("".join(ref))
        a["n"] += 1
        a["roman"] += not has_devanagari(r["hyp"])
    out = {}
    print(f"{'model':<8} {'condition':<12} {'':<7} {'skel WER':>9} {'skel CER':>9} {'roman out':>10}")
    for (m, c, d), a in agg.items():
        row = {
            "skeleton_wer": a["we"] / max(1, a["wn"]),
            "skeleton_cer": a["ce"] / max(1, a["cn"]),
            "roman_share": a["roman"] / a["n"],
        }
        out[f"{m}|{c}|{d}"] = row
        print(f"{m:<8} {c:<12} {d:<7} {row['skeleton_wer']:>8.1%} {row['skeleton_cer']:>9.1%} {row['roman_share']:>10.0%}")
    (path.parent / "asr-eval-skeleton.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
    return out


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    score(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HYPS)
