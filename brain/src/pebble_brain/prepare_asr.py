"""Packages Pebble's speech models (plan 3.2).

Run:  uv run python -m pebble_brain.prepare_asr voice      (the shipped cascade, models/asr-voice/)
      uv run python -m pebble_brain.prepare_asr small      (a Whisper-only package, for evaluation)

The shipped cascade: Dolphin-base CTC (Apache-2.0) for Hindi/Hinglish — 16% CER on FLEURS Hindi at
RTF 0.01 — and Whisper-base for English, which Dolphin doesn't transcribe. Files: ctc-model.int8.onnx,
ctc-tokens.txt, base-*.int8.onnx, base-tokens.txt (hex, below), silero_vad.onnx, HEX_TOKENS.

Whisper token fix:

Why: Whisper's tokens are byte-level, so one Devanagari letter (3 UTF-8 bytes) can be split across two
tokens. sherpa-onnx's Java API turns each token into a string on its own, and a half letter becomes ""
— whole consonants silently vanish ("पर्यावरण" → "र्यारन"). We rewrite the token table so every token's
text is the *hex* of its bytes (plain ASCII, survives any per-token conversion); SpeechRecognizer
joins the hex and decodes UTF-8 once (`HEX_TOKENS` marker file). Nothing else about the model changes.

Output: models/asr-whisper-<size>/  {<size>-encoder.int8.onnx, <size>-decoder.int8.onnx,
<size>-tokens.txt (hex), silero_vad.onnx, HEX_TOKENS}
"""

from __future__ import annotations

import base64
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def hex_tokens(src: pathlib.Path, dst: pathlib.Path) -> int:
    lines = []
    for line in src.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        token, idx = line.rsplit(" ", 1)
        raw = base64.b64decode(token)
        if raw.startswith(b"<|") and raw.endswith(b"|>"):
            enc = token  # special tokens (<|hi|>, <|transcribe|>…) stay as they are: sherpa looks them up by text
        else:
            enc = base64.b64encode(raw.hex().encode("ascii")).decode("ascii")
        lines.append(f"{enc} {idx}")
    dst.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(lines)


def prepare(size: str) -> pathlib.Path:
    src = ROOT / "models" / "asr" / f"sherpa-onnx-whisper-{size}"
    out = ROOT / "models" / f"asr-whisper-{size}"
    out.mkdir(parents=True, exist_ok=True)
    for name in (f"{size}-encoder.int8.onnx", f"{size}-decoder.int8.onnx"):
        shutil.copy2(src / name, out / name)
    shutil.copy2(ROOT / "models" / "asr" / "silero_vad.onnx", out / "silero_vad.onnx")
    n = hex_tokens(src / f"{size}-tokens.txt", out / f"{size}-tokens.txt")
    (out / "HEX_TOKENS").write_text(
        "token text is the hex of its UTF-8 bytes; see pebble_brain/prepare_asr.py\n", encoding="utf-8"
    )
    print(f"{out}: {n} tokens hex-encoded")
    return out


DOLPHIN = "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"


def prepare_voice() -> pathlib.Path:
    """Dolphin-base (Hindi) + Whisper-base (English fallback) + Silero VAD in models/asr-voice/."""
    whisper = prepare("base")
    out = ROOT / "models" / "asr-voice"
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(whisper, out)
    src = ROOT / "models" / "asr" / DOLPHIN
    shutil.copy2(src / "model.int8.onnx", out / "ctc-model.int8.onnx")
    shutil.copy2(src / "tokens.txt", out / "ctc-tokens.txt")
    print(f"{out}: Dolphin-base + Whisper-base + VAD")
    return out


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else "voice"
    prepare_voice() if arg == "voice" else prepare(arg)
