"""Loads Amazon MASSIVE into word-level examples with intent + BIO slot tags, in three scripts.

Each MASSIVE line has e.g. `annot_utt = "मुझे [date : इस सप्ताह] सुबह [time : पांच बजे] जगा दो"`.
We split it into words and tag them:  मुझे/O इस/B-date सप्ताह/I-date सुबह/O पांच/B-time बजे/I-time …

Scripts produced:
  en         English (en-US)
  hi_deva    Hindi in Devanagari (hi-IN)
  hi_roman   the same Hindi, casually romanised by `translit.romanize` (generated, not human-typed)
"""

from __future__ import annotations

import json
import pathlib
import re
from dataclasses import dataclass

from .translit import romanize

RAW = pathlib.Path(__file__).resolve().parents[2] / "data" / "raw" / "massive"
_SLOT = re.compile(r"\[(?P<slot>[^:\]]+?)\s*:\s*(?P<value>[^\]]*?)\]")


@dataclass(frozen=True)
class Example:
    tokens: list[str]
    tags: list[str]
    intent: str
    script: str
    partition: str  # train | dev | test
    #: low | neutral | good, or None = no mood label (left out of the mood loss)
    mood: str | None = None

    @property
    def text(self) -> str:
        return " ".join(self.tokens)


def parse_annotated(annot: str) -> tuple[list[str], list[str]]:
    """'set [time : five pm] alarm' → (['set','five','pm','alarm'], ['O','B-time','I-time','O'])."""
    tokens: list[str] = []
    tags: list[str] = []
    pos = 0
    for m in _SLOT.finditer(annot):
        for w in annot[pos : m.start()].split():
            tokens.append(w)
            tags.append("O")
        for i, w in enumerate(m.group("value").split()):
            tokens.append(w)
            tags.append(("B-" if i == 0 else "I-") + m.group("slot").strip())
        pos = m.end()
    for w in annot[pos:].split():
        tokens.append(w)
        tags.append("O")
    return tokens, tags


def load(scripts: tuple[str, ...] = ("en", "hi_deva", "hi_roman")) -> list[Example]:
    out: list[Example] = []
    files = {"en": "en-US.jsonl", "hi_deva": "hi-IN.jsonl"}
    for script, fname in files.items():
        path = RAW / fname
        if not path.exists():
            raise FileNotFoundError(f"{path} missing — run: uv run python data/download_massive.py")
        for line in path.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            tokens, tags = parse_annotated(row["annot_utt"])
            if not tokens:
                continue
            if script in scripts:
                out.append(Example(tokens, tags, row["intent"], script, row["partition"]))
            if script == "hi_deva" and "hi_roman" in scripts:
                out.append(Example(romanize(tokens, seed=int(row["id"])), tags, row["intent"], "hi_roman", row["partition"]))
    return out


def label_sets(examples: list[Example]) -> tuple[list[str], list[str]]:
    intents = sorted({e.intent for e in examples})
    tags = sorted({t for e in examples for t in e.tags})
    return intents, tags
