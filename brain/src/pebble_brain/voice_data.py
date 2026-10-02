"""Your corrected voice clips → a fine-tuning list for Whisper (plan 3.5; the LoRA run itself is M4/M7).

Run:  uv run python -m pebble_brain.voice_data [path/to/pebble.db]

Reads the opt-in `voice_sample` table read-only and writes models/asr/voice-finetune.jsonl:
{"audio": <wav path>, "text": <what you actually said>, "heard": <what Whisper wrote>}.
Only clips you corrected are ever stored, so every row is a real mistake with its fix.
Nothing is copied or uploaded; the audio stays where the app put it (%APPDATA%\\Pebble\\voice).
"""

from __future__ import annotations

import json
import pathlib
import sqlite3
import sys

from .feedback import default_db

ROOT = pathlib.Path(__file__).resolve().parents[2]


def export(db: pathlib.Path, out: pathlib.Path = ROOT / "models" / "asr" / "voice-finetune.jsonl") -> int:
    if not db.exists():
        print(f"{db} not found")
        return 0
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    if not con.execute("SELECT 1 FROM sqlite_master WHERE name = 'voice_sample'").fetchone():
        print("no voice_sample table yet (app not updated or never opted in)")
        return 0
    rows = [
        r
        for r in con.execute("SELECT wav_path, asr_text, final_text FROM voice_sample ORDER BY at_millis")
        if pathlib.Path(r[0]).exists()
    ]
    con.close()
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as f:
        for wav, heard, text in rows:
            f.write(json.dumps({"audio": wav, "text": text, "heard": heard}, ensure_ascii=False) + "\n")
    print(f"{len(rows)} corrected clips → {out}")
    return len(rows)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    export(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else default_db())
