"""The learning loop: turns what you did in the app into training examples for the next model.

Run:  uv run python -m pebble_brain.feedback            (summary of %APPDATA%\\Pebble\\pebble.db)
      uv run python -m pebble_brain.train_intent --out models/intent-v2 --feedback auto

The app records every command the model handled in `command_feedback` (see shared/.../Brain.sq):
  picked     your "Did you mean…?" choice              → strong label, seen 3× per epoch
  confirmed  Pebble acted and you didn't object         → weak label, seen 1×
  wrong      you tapped "Not what I meant"              → never a positive; listed for review
  taught     a phrase you taught on the Memory page     → strong label, seen 3×
Only the intent is known (not which words are the time / person), so slot loss is skipped for these
examples (tag "" = ignored). Anything close to an eval sentence is dropped, so the gate stays honest.
The database is opened read-only; nothing leaves the laptop.
"""

from __future__ import annotations

import contextlib
import os
import pathlib
import sqlite3
import sys
from collections import Counter

from .massive import Example
from .pebble_data import _words, eval_texts
from .pebble_intents import to_pebble

#: Pebble action → the MASSIVE intent to train on when the model's own finer intent doesn't fit.
ACTION_TO_INTENT = {
    "remind": "calendar_set",
    "reminders_query": "calendar_query",
    "reminder_remove": "calendar_remove",
    "add_note": "lists_createoradd",
    "notes_query": "lists_query",
    "note_remove": "lists_remove",
    "time_query": "datetime_query",
    "chitchat": "general_quirky",
}
WEIGHT = {"picked": 3, "taught": 3, "confirmed": 1}
IGNORE = ""  # slot tag skipped by the loss (train_intent.make_targets)


def default_db() -> pathlib.Path:
    return pathlib.Path(os.environ.get("APPDATA", pathlib.Path.home())) / "Pebble" / "pebble.db"


def load_rows(db: pathlib.Path) -> list[dict]:
    # as_uri() escapes a Windows path (backslashes, drive letter, spaces, "#", "?") the way a file: URI needs.
    uri = pathlib.Path(db).resolve().as_uri() + "?mode=ro"
    with contextlib.closing(sqlite3.connect(uri, uri=True)) as con:
        con.row_factory = sqlite3.Row
        cols = {r[1] for r in con.execute("PRAGMA table_info(command_feedback)")}
        outcome = "outcome" if "outcome" in cols else "'picked' AS outcome"  # databases from before the migration
        return [
            dict(r)
            for r in con.execute(
                f"SELECT text, chosen_action, model_intent, model_confidence, at_millis, {outcome} FROM command_feedback ORDER BY at_millis"
            )
        ]


def to_examples(rows: list[dict], held_out: list[set[str]] | None = None, leak_threshold: float = 0.6):
    """Feedback rows → (training examples with repeats by weight, report). The latest label per sentence wins."""
    held_out = eval_texts() if held_out is None else held_out
    latest: dict[str, dict] = {}
    wrong = []
    for r in rows:
        key = " ".join(r["text"].lower().split())
        if r["outcome"] == "wrong":
            wrong.append(r)
            if key in latest and latest[key]["chosen_action"] == r["chosen_action"]:
                del latest[key]  # the confirmed label for this exact mistake is void
            continue
        latest[key] = r

    out: list[Example] = []
    skipped = Counter()
    for r in latest.values():
        action = r["chosen_action"]
        if action not in ACTION_TO_INTENT:
            skipped["not a model action (rules handle it)"] += 1
            continue
        w = _words(r["text"])
        if any(len(w & e) / len(w | e) >= leak_threshold for e in held_out):
            skipped["too close to an eval sentence"] += 1
            continue
        # Keep the model's finer intent (alarm_set, general_joke…) when it agrees with your action.
        intent = r["model_intent"] if r["model_intent"] and to_pebble(r["model_intent"]) == action else ACTION_TO_INTENT[action]
        tokens = r["text"].split()
        script = "hi_deva" if any("ऀ" <= c <= "ॿ" for c in r["text"]) else "feedback"
        out += [Example(tokens, [IGNORE] * len(tokens), intent, script, "train")] * WEIGHT.get(r["outcome"], 1)

    report = {
        "rows": len(rows),
        "by_outcome": dict(Counter(r["outcome"] for r in rows)),
        "examples": len(out),
        "skipped": dict(skipped),
        "wrong": [(r["text"], r["chosen_action"]) for r in wrong],
    }
    return out, report


def from_db(db: pathlib.Path | None = None):
    db = db or default_db()
    if not db.exists():
        return [], {"rows": 0, "note": f"{db} not found"}
    return to_examples(load_rows(db))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    examples, report = from_db(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None)
    print({k: v for k, v in report.items() if k != "wrong"})
    for text, action in report.get("wrong", []):
        print(f"  wrong: {action:<16} {text!r}   ← worth hand-labelling")
