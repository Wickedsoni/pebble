"""M1: the common base (a multilingual encoder) with two heads trained together.

    tokens ─► encoder (multilingual-e5-small) ─► per-token states ─┬─► slot head   (B-time, I-date, O …)
                                                                     └─► mean pool ─► intent head (60 MASSIVE intents)

Multi-task learning: one loss = intent loss + slot loss, so the shared encoder has to learn both
*what* you want and *which words* carry the details (times, dates, items). That fixes notebook 02's
"remind me to drink water at 4 → water" mistake, which a sentence vector alone can't.
"""

from __future__ import annotations

import json
import pathlib
from dataclasses import dataclass

import torch
from torch import nn
from transformers import AutoModel, AutoTokenizer

BASE = "intfloat/multilingual-e5-small"


class IntentSlotModel(nn.Module):
    def __init__(self, n_intents: int, n_tags: int, base: str = BASE):
        super().__init__()
        self.encoder = AutoModel.from_pretrained(base)
        hidden = self.encoder.config.hidden_size
        self.dropout = nn.Dropout(0.1)
        self.intent_head = nn.Linear(hidden, n_intents)
        self.slot_head = nn.Linear(hidden, n_tags)

    def forward(self, input_ids, attention_mask):
        h = self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(h.dtype)
        pooled = (h * mask).sum(1) / mask.sum(1).clamp(min=1)  # mean over real tokens
        return self.intent_head(self.dropout(pooled)), self.slot_head(self.dropout(h))


@dataclass
class Labels:
    intents: list[str]
    tags: list[str]

    def save(self, path: pathlib.Path) -> None:
        path.write_text(json.dumps({"intents": self.intents, "tags": self.tags}, ensure_ascii=False, indent=1), encoding="utf-8")

    @classmethod
    def load(cls, path: pathlib.Path) -> "Labels":
        d = json.loads(path.read_text(encoding="utf-8"))
        return cls(d["intents"], d["tags"])


def encode_words(tokenizer, words_batch: list[list[str]], max_len: int = 64):
    """Tokenises pre-split words; returns the batch plus, per row, the sub-token index of each word's first piece."""
    # e5 was trained with a "query: " prefix; we add it as an extra leading word and ignore its labels.
    enc = tokenizer([["query:"] + w for w in words_batch], is_split_into_words=True, truncation=True,
                    max_length=max_len, padding=True, return_tensors="pt")
    firsts = []
    for i in range(len(words_batch)):
        seen, idx = set(), []
        for pos, wid in enumerate(enc.word_ids(i)):
            if wid is not None and wid > 0 and wid not in seen:
                seen.add(wid)
                idx.append(pos)
        firsts.append(idx)
    return enc, firsts


class Predictor:
    """Loads a trained checkpoint for evaluation / export."""

    def __init__(self, ckpt: pathlib.Path, device: str | None = None):
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.labels = Labels.load(ckpt / "labels.json")
        self.tokenizer = AutoTokenizer.from_pretrained(ckpt / "tokenizer")
        self.model = IntentSlotModel(len(self.labels.intents), len(self.labels.tags))
        state = torch.load(ckpt / "model.pt", map_location="cpu")
        # Pruned checkpoints (prune_vocab.py) have a smaller embedding table than the base model.
        rows = state["encoder.embeddings.word_embeddings.weight"].shape[0]
        if rows != self.model.encoder.get_input_embeddings().num_embeddings:
            self.model.encoder.resize_token_embeddings(rows)
        self.model.load_state_dict(state)
        self.model.to(self.device).eval()

    @torch.no_grad()
    def predict(self, words_batch: list[list[str]]):
        enc, firsts = encode_words(self.tokenizer, words_batch)
        enc = {k: v.to(self.device) for k, v in enc.items() if k in ("input_ids", "attention_mask")}
        intent_logits, slot_logits = self.model(enc["input_ids"], enc["attention_mask"])
        probs = intent_logits.softmax(-1).cpu()
        slot_ids = slot_logits.argmax(-1).cpu()
        out = []
        for i, words in enumerate(words_batch):
            p, k = probs[i].max(0)
            tags = [self.labels.tags[slot_ids[i, pos]] for pos in firsts[i]]
            tags += ["O"] * (len(words) - len(tags))  # words cut by truncation
            out.append((self.labels.intents[k], float(p), tags))
        return out
