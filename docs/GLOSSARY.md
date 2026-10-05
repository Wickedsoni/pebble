# Glossary

Each term has one meaning in Pebble docs. Do not use a synonym for a term in this list.
See [STYLE-STE.md](STYLE-STE.md) for the writing rules.

| Term | Meaning | Do not use |
|---|---|---|
| **command model** | The ONNX model that reads one text and gives an intent, slots and a mood (intent-v2, e5-small encoder, int8). Code: `OnnxIntentModel`, `ModelManager`. | "intent model" in new docs, "NLU", "classifier" |
| **speech model** | A model that changes speech into text (Dolphin-base CTC, Whisper-base). Code: `SpeechRecognizer`. | "ASR model" in new docs, "voice model" |
| **chat model** | A local large language model that writes small-talk replies ("Smart replies", off by default). It runs as a separate process (`llama-server`) on 127.0.0.1. Code: `LocalChat`, `ChatSafety` (ADR 0012). | "LLM" alone, "AI" |
| **rule** | Deterministic code that reads text without a model: `QuickAddParser`, `HinglishTime`. | "regex", "parser" alone |
| **router** | The code that sends text through the rules, then the command model, and then the decision policy. Code: `CommandRouter`. | "pipeline", "dispatcher" |
| **personal layer** | The code that changes the reading of the command model with your own examples: phrases that you taught, your "Did you mean…" picks and your "Not what I meant" taps. It does not train the model. Code: `PersonalLayer` (ADR 0010). | "fine-tuning", "personalization model" |
| **taught phrase** | A phrase that you gave an action on the Memory page ("Teach Pebble a command"). A row in `command_feedback` with outcome `taught`. | "custom command", "shortcut" |
| **decision policy** | The code that decides to act, or to ask "Did you mean…". It uses the cost of a mistake. Code: `DecisionPolicy`. | "threshold logic" |
| **nudge** | A message from a repeating reminder (for example, "drink water"). The nudge policy decides its time. Code: `NudgePolicy`. | "notification", "ping" |
| **propensity** | The probability that the running nudge policy had of its choice. `ReminderEngine` logs it in each `nudge_decided` log entry. | "score", "confidence" |
| **offline evaluation** | An estimate of the value of a new nudge policy from the logged choices and reactions, with no live test (IPS, SNIPS, ESS). Code: `NudgeIpsEvaluator` (ADR 0016). | "A/B test", "simulation" |
| **HLC** (hybrid logical clock) | A time for a change: the wall-clock milliseconds, a counter and the device id. HLC times have a total order. Code: `Hlc`, `HlcClock` (ADR 0018). | "timestamp" alone, "version" |
| **synced field** | A column that sync copies to other devices. `SyncTable` names each one. | "replicated column" |
| **change journal** | The table `change_journal`. It keeps the newest value and HLC of each synced field of each synced row. Code: `ChangeJournal`. | "changelog", "oplog" |
| **journal entry** | One row in `change_journal`. | "change record" |
| **grave** | The journal entry `deleted_at` of a row that the purge removed. It keeps the row deleted on all devices. | "marker" |
| **history entry** | One row in `change_history`: an old value of one synced field, and why Pebble kept it. Code: `ChangeHistory` (ADR 0019). | "audit row", "backup" |
| **version** | The history entries of one item that one change replaced, shown together. | "revision", "snapshot" |
| **lost edit** | An edit from another device that lost a merge, because this device had a newer edit of the same field. | "conflict", "overwritten change" |
| **restore** | Make an old version the current one, as a new edit. | "undo", "roll back" |
| **restore as a copy** | Make a new item with the fields of a deleted item. The deleted item stays deleted. | "undelete", "recover" |
| **reminder (one-off)** | A reminder that occurs one time, at a set time. Table: `one_off_reminder`. | "alarm", "task" |
| **reminder (repeating)** | A reminder that occurs again after a set interval (eyes, water, stretch). | "habit", "recurring task" |
| **event (calendar)** | An item in the calendar with a start and an end. It can repeat (an RRULE). Table: `calendar_event` (ADR 0014). | "event" without "(calendar)" when the meaning is not clear |
| **occurrence** | One time that a calendar event occurs. A repeating event has many occurrences in one row. Code: `Occurrence`, `RecurrenceExpander`. | "instance", "recurrence" |
| **backup file** | An encrypted copy of the whole database that you save with a passphrase (`.pebblebackup`). Code: `BackupFile`, `Backup` (ADR 0015). | "export" alone, "dump" |
| **log entry** | One row in the `event_log` table. It records one thing that occurred in the app. In code, the type name is `PebbleEvent`. | "event" for a log entry in new docs |
| **peer** | One of your own devices that you paired with Pebble (planned, milestone M9). | "node", "client" |
| **hub** | A family computer that runs Pebble Home. It relays data and runs larger models for the peers (planned, milestone M10). | "server", "cloud" |
| **pairing code** | A short code that you type on a second device to pair it. Pebble uses J-PAKE with this code (see ADR 0006). | "PIN", "password" |
| **model pack** | A signed, versioned set of model files with a manifest that names them. | "model bundle", "weights" |
| **work package (WP)** | One unit of work in the master plan. One WP = one branch = one PR. | "ticket", "task" |
