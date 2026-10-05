# ADR 0012: Answer English small talk with a local chat model; keep Hindi and Hinglish on reviewed lines

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** C5 (local chat). This ADR is the review of the prompt and the safety rules that the plan asks for.

## Context

Small talk uses canned lines (`Replies`). The plan asks for a local chat model as a separate process (llama.cpp `llama-server`), off by default, with a fallback to the canned lines.

We measured four models with `ChatEvalTest` on `brain/eval/chat_v1.jsonl` (24 lines: 8 English, 8 Hinglish, 8 Hindi). The test machine was a Core Ultra 9 185H with 32 GB. A person read every reply.

| Model (licence) | Size | Warm reply p50 / p95 | English | Hinglish | Hindi (Devanagari) |
|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct Q4_K_M (Apache-2.0) | 0.5 GB | 0.4 s / 1.0 s | Generic, acceptable | Repeats the line | Not correct Hindi |
| Qwen3-0.6B Q8_0 (Apache-2.0) | 0.6 GB | 0.6 s / 1.1 s | Acceptable | Repeats the line | Not correct Hindi; one reply had an insult ("साले") |
| **Qwen2.5-1.5B-Instruct Q4_K_M (Apache-2.0)** | **1.1 GB** | **1.0 s / 2.8 s** | **Good** | Often answers in English | The meaning is often lost |
| sarvam-30b Q4_K_M (Apache-2.0, MoE, 2.4B active) | 19.6 GB, 23 GB RAM | no reply | — | — | — |

- **Sarvam:** sarvam-30b was not usable with llama.cpp b11146. It always "thinks" first, also with `--reasoning off` and `enable_thinking: false`, so all 80–200 tokens went to reasoning and the reply was empty. Its reasoning also showed the input changed ("acha gaya**ohyd**"), which shows a tokenizer or template mismatch. It ran at 10–13 tokens/s. `sarvam-1` (2B) is a base model ("cannot be used directly as a chat model") with no licence tag and no GGUF.
- **Safety findings:** with only the first prompt, two models answered "paracetamol or ibuprofen?" with medical advice, and Qwen3-0.6B wrote an insult in Hindi.

## Decision

1. **Model:** Qwen2.5-1.5B-Instruct Q4_K_M in a signed "chat" pack (ADR 0011), with `llama-server` from llama.cpp **b11146** (stable v0.5.0, CPU x64 build) and the licences. It is never in the installer.
2. **Scripts:** the model answers **English only** (`PebbleApp.CHAT_SCRIPTS`). Hindi and Hinglish keep the reviewed canned lines. A new model that passes a person's review of `chat_v1` can add a script.
3. **Never to a model** (`ChatSafety.mayUseModel`): a low mood (word list or mood head), self-harm, health, law and money topics, and scripts outside `CHAT_SCRIPTS`.
4. **Prompt** (`ChatSafety.systemPrompt`):
   - persona: warm desktop pet;
   - one or two short sentences, with no lists, markdown or links;
   - mirror the user's script;
   - "you cannot set reminders, save notes, open apps or look things up, so never say that you did";
   - no medical, legal or money advice;
   - up to 3 matching notes or facts, and the last 3 exchanges.
5. **Shown only if** `ChatSafety.clean` accepts it:
   - `<think>` blocks, role labels and markdown are removed; the reply is cut to 2 sentences and 220 characters;
   - it is refused if it has links, claims an action, uses a health, law or money word, uses an insult (three scripts), uses the wrong script, has Chinese characters, or mostly repeats the user's line.
6. **Process** (`LocalChat`):
   - `--host 127.0.0.1`, a free port, and a random 192-bit key in `LLAMA_API_KEY` (not on the command line);
   - `--offline --no-webui -np 1 -c 2048 --cache-ram 64 --reasoning off`;
   - it starts on the first chat line and stops after 10 idle minutes, when Smart replies is turned off, and on exit;
   - a leftover server from a hard kill is stopped at the next start;
   - a reply waits 6 s at most, then the canned line is used.
7. **Setting:** "Smart replies (chat model)" on Memory → Privacy, off by default. The About page lists the connection.

## Consequences

- **Checked in the built app (2026-10-05):**
  - the chat pack installs from About;
  - the server is ready in 1.3 s and listens only on 127.0.0.1;
  - a request without a key or with a wrong key gets 401, and `/` gets 404 (no web UI);
  - the server log has timings, not text;
  - a server left by a hard kill is replaced at the next start.
- **Memory:** the helper process uses about 1.7 GB while it runs. The Pebble process does not change.
- Hinglish and Hindi users get the same canned lines as before. That is the safe result, not the target.
- Not checked live: the 10-minute idle stop (it uses the same idle logic as `LazyModel`).

## Alternatives

- **sarvam-30b on the laptop.** Not selected: 23 GB of RAM, and no usable reply through llama.cpp b11146. It is a candidate for the family hub (F5/F6), with the runtime that Sarvam recommends.
- **A sub-1B model for all scripts.** Not selected: the Hindi is not correct, and there is a risk of insults.
- **Distillation into one small Hinglish chat model.** This is the real fix for Hindi on small laptops. It is a training run (C6): a larger teacher writes Hindi and Hinglish replies, a person reviews a sample, and a small student is fine-tuned. Then `chat_v1` and a person's review decide if a script can use it.

## Revision (QA P9a): check the port owner before the key is sent

The free port is found with a socket that is closed before `llama-server` binds it. Another local program could take the port in this gap and read the key and the chat text.

- `LocalChat` asks `/health` first. It needs no key. We checked this live with the pinned build b11146: with `LLAMA_API_KEY` set, `/health` gave 503 while the model loaded and then 200, and `/v1/models` gave 401 without the key.
- Only when `/health` answers 200 does Pebble read the listeners on the port with `netstat -ano` (the JDK has no API for this; IPv4 and IPv6, the output is read as ISO-8859-1 because it uses the OEM code page). If the child process is not the only listener, or netstat fails, Pebble sends no key, stops the child, and tries again on a new port (3 tries at most). Without a check, Smart replies stays off.
- A start that was cancelled or closed stops its child at once, also while the model loads. A server left by a hard kill is stopped before a staged chat pack is installed.
- A small gap remains between the check and the request. The key is not sent if the child has already stopped.

