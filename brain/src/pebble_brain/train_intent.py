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

from .intent_model import BASE, MOODS, IntentSlotModel, Labels, encode_words
from .massive import Example, label_sets, load
from .pebble_data import chatify, generate

ROOT = pathlib.Path(__file__).resolve().parents[2]


def batches(examples: list[Example], size: int, shuffle: bool, seed: int = 0):
    order = list(range(len(examples)))
    if shuffle:
        random.Random(seed).shuffle(order)
    for i in range(0, len(order), size):
        yield [examples[j] for j in order[i : i + size]]


#: Small-talk intents can carry feelings ("i'm so tired"), so without a template label their mood is unknown.
SMALL_TALK = {"general_quirky", "general_greet", "general_joke"}


def mood_of(e: Example) -> str | None:
    """Template mood if given; commands are neutral; unlabelled small talk is left out of the mood loss."""
    if e.mood:
        return e.mood
    return None if e.intent in SMALL_TALK or e.script == "feedback" else "neutral"


def make_targets(batch, firsts, labels: Labels, seq_len: int):
    intents = torch.tensor([labels.intents.index(e.intent) for e in batch])
    moods = torch.tensor([MOODS.index(m) if (m := mood_of(e)) else -100 for e in batch])
    tags = torch.full((len(batch), seq_len), -100)  # -100 = ignored by the loss (sub-pieces, padding, prefix)
    for i, e in enumerate(batch):
        for w, pos in enumerate(firsts[i]):
            if e.tags[w]:  # "" = slot unknown (feedback examples): leave it out of the slot loss
                tags[i, pos] = labels.tags.index(e.tags[w])
    return intents, tags, moods


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="models/intent-v0")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--no-pebble", action="store_true", help="MASSIVE only (the v0 recipe)")
    ap.add_argument("--mood-weight", type=float, default=0.5, help="weight of the mood head's loss")
    ap.add_argument("--pebble-repeat", type=int, default=3, help="times each Pebble template sentence is seen per epoch")
    ap.add_argument("--chat", type=float, default=0.3, help="share of Roman-Hindi MASSIVE words given chat spelling")
    ap.add_argument(
        "--feedback", default=None, help=r"'auto' (= %%APPDATA%%\Pebble\pebble.db) or a path: train on your in-app labels too"
    )
    args = ap.parse_args()
    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(0)

    data = load()
    if args.chat > 0:
        rng = random.Random(0)
        data = [
            Example(chatify(e.tokens, rng, args.chat), e.tags, e.intent, e.script, e.partition)
            if e.script == "hi_roman" and e.partition == "train"
            else e
            for e in data
        ]
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
    if args.feedback:
        from .feedback import from_db

        fb, report = from_db(None if args.feedback == "auto" else pathlib.Path(args.feedback))
        print(f"feedback: {report}")
        train += fb
    print(f"train {len(train)}  dev {len(dev)}  intents {len(labels.intents)}  tags {len(labels.tags)}  on {device}")

    tokenizer = AutoTokenizer.from_pretrained(BASE)
    model = IntentSlotModel(len(labels.intents), len(labels.tags)).to(device)
    # Heads learn fast from scratch; the pretrained encoder gets a gentler learning rate.
    opt = torch.optim.AdamW(
        [
            {"params": model.encoder.parameters(), "lr": args.lr},
            {
                "params": [p for head in (model.intent_head, model.slot_head, model.mood_head) for p in head.parameters()],
                "lr": args.lr * 20,
            },
        ],
        weight_decay=0.01,
    )
    steps = args.epochs * ((len(train) + args.batch - 1) // args.batch)
    sched = get_linear_schedule_with_warmup(opt, int(0.06 * steps), steps)
    ce = nn.CrossEntropyLoss(ignore_index=-100)
    counts = torch.tensor([sum(mood_of(e) == m for e in train) for m in MOODS], dtype=torch.float)
    mood_ce = nn.CrossEntropyLoss(weight=(counts.sum() / (len(MOODS) * counts.clamp(min=1))).to(device), ignore_index=-100)
    print(f"mood labels: {dict(zip(MOODS, counts.int().tolist()))}")
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    for epoch in range(args.epochs):
        model.train()
        t0, total, n = time.time(), 0.0, 0
        for batch in batches(train, args.batch, shuffle=True, seed=epoch):
            enc, firsts = encode_words(tokenizer, [e.tokens for e in batch])
            intents, tags, moods = make_targets(batch, firsts, labels, enc["input_ids"].shape[1])
            with torch.autocast(device_type=device, dtype=torch.float16, enabled=device == "cuda"):
                il, sl, ml = model(enc["input_ids"].to(device), enc["attention_mask"].to(device))
                loss = ce(il.float(), intents.to(device)) + ce(sl.float().transpose(1, 2), tags.to(device))
                if (moods != -100).any():
                    # Class-balanced: neutral commands outnumber feelings ~50:1.
                    loss = loss + args.mood_weight * mood_ce(ml.float(), moods.to(device))
            opt.zero_grad()
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()
            sched.step()
            total += loss.item()
            n += 1
        acc = dev_intent_accuracy(model, tokenizer, dev, labels, device)
        pacc = dev_intent_accuracy(model, tokenizer, pebble_dev, labels, device) if pebble_dev else float("nan")
        print(
            f"epoch {epoch + 1}: loss {total / n:.3f}  dev intent acc {acc:.1%}  pebble dev {pacc:.1%}  ({time.time() - t0:.0f}s)"
        )

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
        il, _, _ = model(enc["input_ids"].to(device), enc["attention_mask"].to(device))
        right += sum(labels.intents[k] == e.intent for k, e in zip(il.argmax(-1).tolist(), batch))
    model.train()
    return right / len(dev)


if __name__ == "__main__":
    main()
