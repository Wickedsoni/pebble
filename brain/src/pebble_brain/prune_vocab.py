"""Shrinks a trained checkpoint's vocabulary to English + Hindi (Devanagari and Roman).

Run:  uv run python -m pebble_brain.prune_vocab models/intent-v0 models/intent-v0-pruned [--top-k 20000]

Why: multilingual-e5-small has 250k sentencepiece tokens for ~100 languages; the embedding table is
~85% of the model. Pebble needs two languages.

What we keep (the tokenizer has no byte fallback, so coverage matters):
  1. special tokens (<s> <pad> </s> <unk> <mask>)
  2. every piece used to tokenise the training data: MASSIVE train+dev in all three scripts, the same
     Roman Hindi in chat spelling (krna, rha, h), Pebble's template sentences and your feedback —
     anything the model learned from must tokenise exactly as it did in training
  3. every 1–2 character piece made only of Latin / Devanagari / digits / punctuation — so any
     unseen word in those scripts can still be spelled out, never becoming <unk>
  4. the top-K highest-scoring pieces in those scripts, so unseen words get natural splits
Unigram tokenisation picks the best path among available pieces; removing pieces only removes
alternatives, so any text whose original pieces are all kept tokenises *identically*.

Checks (on text NOT used to choose the vocabulary): MASSIVE test + the Pebble eval set —
identical-tokenisation rate, <unk> rate, then the normal evaluation of the pruned model.
"""

from __future__ import annotations

import argparse
import random
import json
import pathlib
import re
import shutil

import torch
from transformers import AutoTokenizer

from .feedback import from_db
from .massive import load
from .pebble_data import chatify, generate

ROOT = pathlib.Path(__file__).resolve().parents[2]
_ALLOWED = re.compile(r"^[▁A-Za-z0-9ऀ-ॿ‌‍\s.,!?'\"()\-:;/&%+@#₹$*=<>\[\]{}|_~`^]+$")


def choose(tokenizer, vocab: list, top_k: int) -> list[int]:
    keep = {0, 1, 2, 3, len(vocab) - 1}  # specials incl. <mask> (last)
    massive = [e for e in load() if e.partition in ("train", "dev")]
    rng = random.Random(0)
    texts = [e.tokens for e in massive]
    texts += [chatify(e.tokens, rng, p=1.0) for e in massive if e.script == "hi_roman"]
    texts += [e.tokens for e in generate()]
    texts += [e.tokens for e in from_db()[0]]
    for i in range(0, len(texts), 512):
        for ids in tokenizer(texts[i:i + 512], is_split_into_words=True)["input_ids"]:
            keep.update(ids)
    in_corpus = len(keep)
    allowed = [(i, piece, score) for i, (piece, score) in enumerate(vocab) if _ALLOWED.match(piece)]
    keep.update(i for i, piece, _ in allowed if len(piece.replace("▁", "")) <= 2)
    for i, _, _ in sorted(allowed, key=lambda x: -x[2])[:top_k]:
        keep.add(i)
    print(f"pieces: corpus {in_corpus}, +short/top-K in en/hi scripts → {len(keep)} of {len(vocab)}")
    return sorted(keep)


def prune(src: pathlib.Path, dst: pathlib.Path, top_k: int) -> None:
    tok = AutoTokenizer.from_pretrained(src / "tokenizer")
    tj = json.loads((src / "tokenizer" / "tokenizer.json").read_text(encoding="utf-8"))
    vocab = tj["model"]["vocab"]
    keep = choose(tok, vocab, top_k)
    remap = {old: new for new, old in enumerate(keep)}

    # 1) Tokenizer: new vocab list, remapped special-token ids.
    tj["model"]["vocab"] = [vocab[i] for i in keep]
    tj["model"]["unk_id"] = remap[tj["model"]["unk_id"]]
    for a in tj["added_tokens"]:
        a["id"] = remap[a["id"]]
    for spec in tj.get("post_processor", {}).get("special_tokens", {}).values():
        spec["ids"] = [remap[i] for i in spec["ids"]]
    dst.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src / "tokenizer", dst / "tokenizer", dirs_exist_ok=True)
    (dst / "tokenizer" / "tokenizer.json").write_text(json.dumps(tj, ensure_ascii=False), encoding="utf-8")
    shutil.copy(src / "labels.json", dst / "labels.json")

    # 2) Model: keep only the embedding rows we kept, in the same order.
    state = torch.load(src / "model.pt", map_location="cpu")
    key = "encoder.embeddings.word_embeddings.weight"
    state[key] = state[key][torch.tensor(keep)].clone()
    torch.save(state, dst / "model.pt")
    print(f"embedding table {len(vocab)} → {len(keep)} rows")

    verify(src, dst, remap)


def verify(src: pathlib.Path, dst: pathlib.Path, remap: dict[int, int]) -> None:
    old = AutoTokenizer.from_pretrained(src / "tokenizer")
    new = AutoTokenizer.from_pretrained(dst / "tokenizer")
    unk = new.unk_token_id
    held_out = [e.tokens for e in load() if e.partition == "test"]
    for name in ("v0", "v1"):
        held_out += [json.loads(l)["text"].split() for l in (ROOT / "eval" / f"pebble_commands_{name}.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    same = unks = 0
    for words in held_out:
        a = old(words, is_split_into_words=True)["input_ids"]
        b = new(words, is_split_into_words=True)["input_ids"]
        same += [remap.get(i, -1) for i in a] == b
        unks += unk in b and unk not in a
    print(f"held-out sentences: {same / len(held_out):.1%} tokenise identically, {unks} gained an <unk> (of {len(held_out)})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", nargs="?", default="models/intent-v0")
    ap.add_argument("dst", nargs="?", default="models/intent-v0-pruned")
    ap.add_argument("--top-k", type=int, default=20000)
    a = ap.parse_args()
    prune(ROOT / a.src, ROOT / a.dst, a.top_k)


if __name__ == "__main__":
    main()
