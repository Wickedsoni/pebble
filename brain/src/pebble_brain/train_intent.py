"""Trains the M1 intent + slot model on MASSIVE (en, hi_deva, hi_roman).

Run:  uv run python -m pebble_brain.train_intent --out models/intent-v1
      (--no-pebble --chat 0 reproduces intent-v0: MASSIVE only)

Saves model.pt, labels.json and the tokenizer to --out, then runs the full evaluation.
"""

from __future__ import annotations

import argparse
import pathlib
import random
import time

import torch
from torch import nn
from transformers import AutoTokenizer, get_linear_schedule_with_warmup

from .intent_model import BASE, IntentSlotModel, Labels, encode_words
from .massive import Example, label_sets, load
from .pebble_data import chatify, generate

ROOT = pathlib.Path(__file__).resolve().parents[2]


def batches(examples: list[Example], size: int, shuffle: bool, seed: int = 0):
    order = list(range(len(examples)))
    if shuffle:
        random.Random(seed).shuffle(order)
    for i in range(0, len(order), size):
        yield [examples[j] for j in order[i:i + size]]


def make_targets(batch, firsts, labels: Labels, seq_len: int):
    intents = torch.tensor([labels.intents.index(e.intent) for e in batch])
    tags = torch.full((len(batch), seq_len), -100)  # -100 = ignored by the loss (sub-pieces, padding, prefix)
    for i, e in enumerate(batch):
        for w, pos in enumerate(firsts[i]):
            tags[i, pos] = labels.tags.index(e.tags[w])
    return intents, tags


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="models/intent-v0")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--no-pebble", action="store_true", help="MASSIVE only (the v0 recipe)")
    ap.add_argument("--pebble-repeat", type=int, default=3, help="times each Pebble template sentence is seen per epoch")
    ap.add_argument("--chat", type=float, default=0.3, help="share of Roman-Hindi MASSIVE words given chat spelling")
    args = ap.parse_args()
    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(0)

    data = load()
    if args.chat > 0:
        rng = random.Random(0)
        data = [Example(chatify(e.tokens, rng, args.chat), e.tags, e.intent, e.script, e.partition)
                if e.script == "hi_roman" and e.partition == "train" else e for e in data]
    labels = Labels(*label_sets(data))
    train = [e for e in data if e.partition == "train"]
    dev = [e for e in data if e.partition == "dev"]
    pebble_dev: list[Example] = []
    if not args.no_pebble:
        pebble = generate()
        train += [e for e in pebble if e.partition == "train"] * args.pebble_repeat
        pebble_dev = [e for e in pebble if e.partition == "dev"]
        unknown = {t for e in pebble for t in e.tags} - set(labels.tags)
        assert not unknown, f"Pebble data uses tags MASSIVE doesn't have: {unknown}"
    print(f"train {len(train)}  dev {len(dev)}  intents {len(labels.intents)}  tags {len(labels.tags)}  on {device}")

    tokenizer = AutoTokenizer.from_pretrained(BASE)
    model = IntentSlotModel(len(labels.intents), len(labels.tags)).to(device)
    # Heads learn fast from scratch; the pretrained encoder gets a gentler learning rate.
    opt = torch.optim.AdamW([
        {"params": model.encoder.parameters(), "lr": args.lr},
        {"params": list(model.intent_head.parameters()) + list(model.slot_head.parameters()), "lr": args.lr * 20},
    ], weight_decay=0.01)
    steps = args.epochs * ((len(train) + args.batch - 1) // args.batch)
    sched = get_linear_schedule_with_warmup(opt, int(0.06 * steps), steps)
    ce = nn.CrossEntropyLoss(ignore_index=-100)
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    for epoch in range(args.epochs):
        model.train()
        t0, total, n = time.time(), 0.0, 0
        for batch in batches(train, args.batch, shuffle=True, seed=epoch):
            enc, firsts = encode_words(tokenizer, [e.tokens for e in batch])
            intents, tags = make_targets(batch, firsts, labels, enc["input_ids"].shape[1])
            with torch.autocast(device_type=device, dtype=torch.float16, enabled=device == "cuda"):
                il, sl = model(enc["input_ids"].to(device), enc["attention_mask"].to(device))
                loss = ce(il.float(), intents.to(device)) + ce(sl.float().transpose(1, 2), tags.to(device))
            opt.zero_grad()
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()
            sched.step()
            total += loss.item(); n += 1
        acc = dev_intent_accuracy(model, tokenizer, dev, labels, device)
        pacc = dev_intent_accuracy(model, tokenizer, pebble_dev, labels, device) if pebble_dev else float("nan")
        print(f"epoch {epoch + 1}: loss {total / n:.3f}  dev intent acc {acc:.1%}  pebble dev {pacc:.1%}  ({time.time() - t0:.0f}s)")

    torch.save(model.state_dict(), out / "model.pt")
    labels.save(out / "labels.json")
    tokenizer.save_pretrained(out / "tokenizer")
    print("saved", out)

    from .evaluate import evaluate
    evaluate(out, "v1")


@torch.no_grad()
def dev_intent_accuracy(model, tokenizer, dev, labels: Labels, device: str) -> float:
    model.eval()
    right = 0
    for batch in batches(dev, 128, shuffle=False):
        enc, _ = encode_words(tokenizer, [e.tokens for e in batch])
        il, _ = model(enc["input_ids"].to(device), enc["attention_mask"].to(device))
        right += sum(labels.intents[k] == e.intent for k, e in zip(il.argmax(-1).tolist(), batch))
    model.train()
    return right / len(dev)


if __name__ == "__main__":
    main()
