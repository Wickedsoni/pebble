"""Round trip: app-style feedback rows in SQLite → training examples.  Run: uv run python -m unittest tests/test_feedback.py"""

import pathlib
import sqlite3
import tempfile
import unittest

from pebble_brain.feedback import IGNORE, from_db


class FeedbackTest(unittest.TestCase):
    def make_db(self, rows, with_outcome=True) -> pathlib.Path:
        path = pathlib.Path(tempfile.mkdtemp()) / "pebble.db"
        con = sqlite3.connect(path)
        extra = ", outcome TEXT NOT NULL DEFAULT 'picked'" if with_outcome else ""
        con.execute(
            "CREATE TABLE command_feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, chosen_action TEXT NOT NULL,"
            f" model_intent TEXT, model_confidence REAL, at_millis INTEGER NOT NULL{extra})"
        )
        cols = "text, chosen_action, model_intent, model_confidence, at_millis" + (", outcome" if with_outcome else "")
        con.executemany(f"INSERT INTO command_feedback({cols}) VALUES ({', '.join('?' * len(rows[0]))})", rows)
        con.commit()
        con.close()
        return path

    def test_outcomes_weights_and_wrong(self):
        db = self.make_db(
            [
                ("pebble tu toh kamaal hai", "chitchat", "general_quirky", 0.4, 1, "picked"),  # 3×, model's finer intent kept
                (
                    "pani ki bottle bharni hai shaam ko",
                    "remind",
                    "lists_createoradd",
                    0.5,
                    2,
                    "picked",
                ),  # model disagreed → calendar_set
                ("doodh khatam ho gaya", "add_note", "lists_createoradd", 0.9, 3, "confirmed"),  # 1×
                ("phir se batao kya kaha", "add_note", "lists_createoradd", 0.7, 4, "confirmed"),
                ("phir se batao kya kaha", "add_note", "lists_createoradd", 0.7, 5, "wrong"),  # voids the confirmed row
                ("do glass paani piya", "log_water", None, None, 6, "picked"),  # rules' job, not the model's
                ("tum bahut cute ho yaar", "chitchat", "general_quirky", 0.4, 7, "picked"),  # eval v1 sentence: never train on it
            ]
        )
        examples, report = from_db(db)
        got = sorted({(" ".join(e.tokens), e.intent) for e in examples})
        self.assertEqual(
            got,
            [
                ("doodh khatam ho gaya", "lists_createoradd"),
                ("pani ki bottle bharni hai shaam ko", "calendar_set"),
                ("pebble tu toh kamaal hai", "general_quirky"),
            ],
        )
        self.assertEqual(len(examples), 3 + 3 + 1)
        self.assertTrue(all(t == IGNORE for e in examples for t in e.tags))
        self.assertEqual(report["wrong"], [("phir se batao kya kaha", "add_note")])
        self.assertEqual(report["skipped"], {"not a model action (rules handle it)": 1, "too close to an eval sentence": 1})

    def test_database_from_before_the_migration(self):
        db = self.make_db([("pebble tu toh kamaal hai", "chitchat", "general_quirky", 0.4, 1)], with_outcome=False)
        examples, report = from_db(db)
        self.assertEqual(report["by_outcome"], {"picked": 1})
        self.assertEqual(len(examples), 3)

    def test_path_with_special_characters(self):
        # A Windows-style path with a space and "#" broke the old hand-built "file:" URI.
        db = self.make_db([("pebble tu toh kamaal hai", "chitchat", "general_quirky", 0.4, 1, "picked")])
        odd = db.parent / "my data #1"
        odd.mkdir()
        moved = odd / "pebble.db"
        db.replace(moved)
        examples, report = from_db(moved)
        self.assertEqual(report["rows"], 1)
        self.assertEqual(len(examples), 3)


if __name__ == "__main__":
    unittest.main()
