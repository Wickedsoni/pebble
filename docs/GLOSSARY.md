# Glossary

Each term has one meaning in Pebble docs. Do not use a synonym for a term in this list.
See [STYLE-STE.md](STYLE-STE.md) for the writing rules.

| Term | Meaning | Do not use |
|---|---|---|
| **command model** | The ONNX model that reads one text and gives an intent, slots and a mood (intent-v2, e5-small encoder, int8). Code: `OnnxIntentModel`, `ModelManager`. | "intent model" in new docs, "NLU", "classifier" |
| **speech model** | A model that changes speech into text (Dolphin-base CTC, Whisper-base). Code: `SpeechRecognizer`. | "ASR model" in new docs, "voice model" |
| **chat model** | A local large language model that writes free replies (planned, milestone M5). It runs as a separate process. | "LLM" alone, "AI" |
| **rule** | Deterministic code that reads text without a model: `QuickAddParser`, `HinglishTime`. | "regex", "parser" alone |
| **router** | The code that sends text through the rules, then the command model, and then the decision policy. Code: `CommandRouter`. | "pipeline", "dispatcher" |
| **decision policy** | The code that decides to act, or to ask "Did you mean…". It uses the cost of a mistake. Code: `DecisionPolicy`. | "threshold logic" |
| **nudge** | A message from a repeating reminder (for example, "drink water"). The nudge policy decides its time. Code: `NudgePolicy`. | "notification", "ping" |
| **reminder (one-off)** | A reminder that occurs one time, at a set time. Table: `one_off_reminder`. | "alarm", "task" |
| **reminder (repeating)** | A reminder that occurs again after a set interval (eyes, water, stretch). | "habit", "recurring task" |
| **event (calendar)** | An item in the calendar with a start time (planned, milestone M8). | "event" without "(calendar)" when the meaning is not clear |
| **log entry** | One row in the `event_log` table. It records one thing that occurred in the app. In code, the type name is `PebbleEvent`. | "event" for a log entry in new docs |
| **peer** | One of your own devices that you paired with Pebble (planned, milestone M9). | "node", "client" |
| **hub** | A family computer that runs Pebble Home. It relays data and runs larger models for the peers (planned, milestone M10). | "server", "cloud" |
| **pairing code** | A short code that you type on a second device to pair it. Pebble uses J-PAKE with this code (see ADR 0006). | "PIN", "password" |
| **model pack** | A signed, versioned set of model files with a manifest that names them. | "model bundle", "weights" |
| **work package (WP)** | One unit of work in the master plan. One WP = one branch = one PR. | "ticket", "task" |
