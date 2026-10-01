# Next session — where we are

## Done
- **M0:** `brain/` Python 3.11 env (PyTorch CUDA on the RTX 4050), learning notebooks 01–02.
- **M1 command model:** MASSIVE (CC BY 4.0) + Hinglish transliteration → multilingual-e5-small with intent + slot heads,
  vocab-pruned and int8-quantised (31 MB model + 1.9 MB tokenizer). MASSIVE intent acc en 87.9 / hi 85.9 / Roman 79.1%.
- **Kotlin runtime:** ONNX Runtime + HF tokenizer in the app with exact Python parity (`OnnxParityTest`);
  `ModelManager` loads lazily, verifies the SHA-256 from `brain/models/manifest.json`, unloads after 10 idle minutes.
- **Router:** rules → model → "Did you mean…?" (answers saved to `command_feedback` for retraining);
  Hindi/Hinglish rules for remember, "har N minute", water logging and low mood; `HinglishTime` reads times in 3 scripts.
- Verified in the running app: "kal shaam 7 baje mummy ko call karne ki yaad dila dena" → reminder "Mummy call karne",
  tomorrow 7:00 PM (97% sure). 40/40 tests pass. App: 256 MB with the model loaded, 0.5% of one core idle.

## Next (from the plan)
1. Replace the draft eval set (`brain/eval/pebble_commands_v0.jsonl`) with ~50 real commands from you.
2. Retrain script that also uses `command_feedback` rows (your "Did you mean" picks) — export from `%APPDATA%\Pebble\pebble.db`.
3. Weekday times ("friday wali meeting") in `HinglishTime` (needs today's date passed in).
4. M2: semantic memory search + few-shot prototypes on the same encoder.
5. Parallel track: Claude Code task delegation with pet status (working / needs input / done).
6. Packaging: copy the model into the installer (`%APPDATA%\Pebble\models\intent`) so it works outside the dev layout.
