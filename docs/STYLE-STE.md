# Writing style: ASD-STE100 Simplified Technical English

Pebble docs use ASD-STE100 Simplified Technical English (STE).
STE makes text short, clear and easy to translate.
This file gives the rules that Pebble uses.

## Scope

Use these rules for new and rewritten docs:
- `docs/ARCHITECTURE.md`
- ADRs in `docs/adr/`
- specs and design notes
- `PRIVACY.md` and `SECURITY.md`
- release notes in `docs/releases/`
- the interview pack in `docs/interview/`

Code comments do not use these rules. They keep the style of the surrounding code.

## Limit of these rules

The official ASD-STE100 dictionary is not available in this repo.
Thus, the word-level compliance is approximate.
The sentence rules below are exact. Obey them.

## Sentences

1. Write procedural sentences with 20 words or fewer.
2. Write descriptive sentences with 25 words or fewer.
3. Write paragraphs with 6 sentences or fewer. Each paragraph has one topic.
4. Do not omit articles ("a", "an", "the") where normal English uses them.

## Instructions

1. Write instructions in the imperative: "Run the tests."
2. Write one instruction in each sentence.
3. Put a warning or a caution **before** the step that it applies to.
4. Use a numbered list for steps in sequence.

## Verbs

1. Use the active voice: "The router sends the text to the model."
   Do not write: "The text is sent to the model by the router."
2. Use only simple tenses: simple present, simple past and simple future.
3. Do not use "-ing" verb forms, except in technical names (for example, "Thompson sampling").
4. Prefer simple verbs: *use, make, start, stop, show, send, keep, remove, get, put, find, give*.

## Words

1. One word has one meaning. One meaning has one word.
2. Use the terms in [GLOSSARY.md](GLOSSARY.md). Do not use synonyms for the same thing.
3. Write noun clusters of 3 words or fewer.
   - Write: "the list of peers that are paired".
   - Do not write: "paired peer list sync state".
4. Use vertical lists for 3 or more items.

## Examples

| Do not write | Write |
|---|---|
| The model is loaded lazily when it is needed. | Pebble loads the model only when it needs it. |
| Running the tests is required before merging. | Run the tests before you merge. |
| Run `reset`, which will delete your data. | **Caution:** The next step deletes your data. 1. Run `reset`. |
| The sync engine conflict resolution strategy uses LWW. | The sync engine uses "last writer wins" (LWW) when two changes conflict. |

## Checker (planned)

The master plan adds `tools/docs/ste_check.py` (Python standard library only). It will flag:
- sentences over the length limits;
- "-ing" words that are not in the glossary;
- passive patterns (`is|are|was|were|be|been` + a word that ends in "ed");
- noun clusters of more than 3 words (heuristic).

CI will run it on `docs/**/*.md` as a warning only.
