"""Exports a trained intent+slot checkpoint to ONNX, quantises it to int8, and checks it.

Run:  uv run python -m pebble_brain.export_onnx models/intent-v0

Produces in the checkpoint folder:
  intent.onnx         fp32 graph: (input_ids, attention_mask) → (intent_logits, slot_logits)
  intent.int8.onnx    dynamic int8 quantisation — what the Kotlin app ships (≈4× smaller, faster on CPU)
  tokenizer/tokenizer.json   loaded in Kotlin by a HuggingFace tokenizers binding
Then compares int8 vs PyTorch on the Pebble eval set and measures ONNX Runtime CPU latency.
"""

from __future__ import annotations

import json
import pathlib
import sys
import time

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

from .evaluate import EVAL_SET, pebble_eval
from .intent_model import MOODS, Predictor, encode_words

ROOT = pathlib.Path(__file__).resolve().parents[2]


class _Graph(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask):
        return self.model(input_ids, attention_mask)


def export(ckpt: pathlib.Path) -> None:
    pred = Predictor(ckpt, device="cpu")
    enc, _ = encode_words(pred.tokenizer, [["remind", "me", "at", "5"]])
    fp32 = ckpt / "intent.onnx"
    torch.onnx.export(
        _Graph(pred.model), (enc["input_ids"], enc["attention_mask"]), str(fp32),
        input_names=["input_ids", "attention_mask"], output_names=["intent_logits", "slot_logits", "mood_logits"],
        dynamic_axes={"input_ids": {0: "batch", 1: "seq"}, "attention_mask": {0: "batch", 1: "seq"},
                      "intent_logits": {0: "batch"}, "slot_logits": {0: "batch", 1: "seq"}, "mood_logits": {0: "batch"}},
        opset_version=17, dynamo=False,
    )
    int8 = ckpt / "intent.int8.onnx"
    # Per-channel scales keep accuracy (per-tensor int8 lost ~3 points on the Pebble eval set).
    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8, per_channel=True)
    print(f"fp32 {fp32.stat().st_size / 2**20:.0f} MB  →  int8 {int8.stat().st_size / 2**20:.0f} MB")

    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(int8), opts, providers=["CPUExecutionProvider"])

    rows = [json.loads(l) for l in pebble_eval(EVAL_SET).read_text(encoding="utf-8").splitlines() if l.strip()]
    torch_preds = [p[0] for p in pred.predict([r["text"].split() for r in rows])]
    agree, times = 0, []
    for r, want in zip(rows, torch_preds):
        e, _ = encode_words(pred.tokenizer, [r["text"].split()])
        feeds = {"input_ids": e["input_ids"].numpy().astype(np.int64), "attention_mask": e["attention_mask"].numpy().astype(np.int64)}
        t0 = time.perf_counter()
        il = sess.run(None, feeds)[0]
        times.append((time.perf_counter() - t0) * 1000)
        agree += pred.labels.intents[int(il[0].argmax())] == want
    print(f"int8 agrees with PyTorch on {agree}/{len(rows)} Pebble commands")

    # Calibrate the int8 model (writes "temperature" into labels.json); parity below uses the same T.
    from .calibrate import calibrate
    cal = calibrate(ckpt)
    temperature, mood_t = cal["temperature"], cal.get("mood_temperature")

    # parity.json: what the int8 model says for every eval sentence, so Kotlin can prove it reads
    # commands identically (OnnxParityTest): token ids, intent, confidence, slot tags, mood.
    parity = []
    for name in ("pebble_commands_v0.jsonl", "pebble_commands_v1.jsonl", "mood_v1.jsonl"):
        path = ROOT / "eval" / name
        for line in path.read_text(encoding="utf-8").splitlines() if path.exists() else []:
            if not line.strip():
                continue
            words = json.loads(line)["text"].split()
            e, firsts = encode_words(pred.tokenizer, [words])
            il, sl, *rest = sess.run(None, {"input_ids": e["input_ids"].numpy().astype(np.int64),
                                            "attention_mask": e["attention_mask"].numpy().astype(np.int64)})
            z = il[0] / temperature
            probs = np.exp(z - z.max()); probs /= probs.sum()
            k = int(probs.argmax())
            parity.append({"text": " ".join(words), "input_ids": e["input_ids"][0].tolist(), "intent": pred.labels.intents[k],
                           "p": float(probs[k]), "tags": [pred.labels.tags[int(sl[0, i].argmax())] for i in firsts[0]]})
            if rest and mood_t:
                mz = rest[0][0] / mood_t
                mp = np.exp(mz - mz.max())
                mp /= mp.sum()
                parity[-1]["mood"] = MOODS[int(mp.argmax())]
                parity[-1]["mood_p"] = float(mp.max())
    (ckpt / "parity.json").write_text(json.dumps(parity, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"parity.json: {len(parity)} reference readings")
    print(f"ONNX Runtime int8 CPU latency (4 threads): median {np.median(times):.1f} ms, p95 {np.percentile(times, 95):.1f} ms")

    pred.tokenizer.save_pretrained(ckpt / "tokenizer")  # includes tokenizer.json for Kotlin


if __name__ == "__main__":
    export(ROOT / (sys.argv[1] if len(sys.argv) > 1 else "models/intent-v0"))
