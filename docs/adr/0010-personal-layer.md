# ADR 0010: Blend your own examples into the model with a gated k-NN

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** C3 (personal layer + teach a command). This ADR is the review of the formula that the plan asks for.

## Context

The plan gives this formula for the personal layer:
- neighbours: your examples with cosine ≥ τ = 0.90;
- weight `w = n / (n + k)`, k = 3, n = the number of neighbours;
- `p' = (1 − w) · p_model + w · p_knn`, normalised again;
- examples: `command_feedback` rows `picked`, `confirmed` (positive) and `wrong` (negative), plus taught phrases.

We measured the sentence embedding of `intent-v3-pruned` on `brain/eval/pebble_commands_v0.jsonl` (examples) and `pebble_commands_v1.jsonl` (sentences), with only the actions that the model decides:
- Pairs with cosine ≥ 0.80 have the same action 100% of the time. The embedding groups sentences by the model's intent. Thus a neighbour usually agrees with the model. The layer helps only where the model is wrong.
- A sentence that the model reads wrongly is near the sentences of the wrong intent. A correction for it can thus spread to those sentences.
- At τ = 0.90, one example is a neighbour of 3.5 of 56 eval sentences on average, and of 13 at most.
- With a second gate (Jaccard of the words ≥ 0.2), one example reaches 0.5 sentences on average, and 3 at most.

Other problems in the plan's formula:
- `confirmed` rows are what the model did. If they count as votes, a mistake that you did not correct becomes stronger each time.
- With k = 3 and n = neighbour count, 3 taught phrases give w = 0.5. If the model gives 0.1 to the taught action, p' = 0.55. That is less than every act threshold, so a taught phrase does not work.
- Normalising again after a negative vote gives the removed probability to the other actions. With p = 0.9 for the wrong action, Pebble then does the same wrong thing again.

## Decision

`PersonalLayer` (`shared/.../brain/PersonalLayer.kt`) uses this formula:
1. **Examples:** `taught` and `picked` rows vote +1; `wrong` rows vote −1. The latest label for each (sentence, action) wins. `confirmed` rows are not examples.
2. **Neighbours:** the same sentence (lower case, single spaces) counts 4. Up to 5 other sentences count `κ = (cos − 0.90) / 0.10` each, if cos ≥ 0.90 **and** Jaccard of the words ≥ 0.2.
3. **Blend:** with P(a) the positive votes, N(a) the negative votes, n = ΣP, K = 2 and w = n / (n + K):
   `p'(a) = (1 − w) · p(a) · K / (N(a) + K) + w · P(a) / n`.
   We do not normalise again: the removed probability is doubt, and `DecisionPolicy` asks.
4. **Limit:** the layer does not lift a removal above 0.85 (the act threshold is 0.9). The "Teach" card does not offer removals.
5. **Context prior:** "Did you mean…" options are sorted by `p' · max(1, sqrt(8 · share))`. The share is how often you used the action (picked or confirmed) in this 3-hour slot of the day, with +1 smoothing. The prior only lifts an action. It never pushes one down.
6. `DecisionPolicy` thresholds do not change. The hook is in `CommandRouter.route` and `ask`, right after `understand(text)`.

With these numbers: one taught phrase, said again exactly, gives w = 2/3. A model probability of 0.1 then becomes 0.70, and Pebble acts. One "Not what I meant" on the same sentence takes 2/3 of the model's probability away, and Pebble asks.

## Consequences

- **Prequential replay gate** (`PersonalReplayTest`, `brain/eval/personal_replay_v1.jsonl`, 39 sentences and 3 taught phrases):
  - right: from 14 to 27;
  - wrong actions: from 12 to 4.
- **The frozen eval set v1, with the layer of that user:** 68/68 right or right first choice, and 0 wrong actions. The numbers do not change.
- The probabilities after the layer are not calibrated. The thresholds still apply to them.
- Taught phrases are `command_feedback` rows with outcome `taught`. Thus no migration is necessary, and `brain/feedback.py` uses them for training (weight 3).
- "Forget what you taught me" deletes the taught rows, and sets `brain.personalSince`. The layer then ignores your earlier picks, but they stay in the database as training labels.
- The embeddings of the examples are kept in memory, and are made again when the model changes. Before the command model loads, only the same sentence counts.
- The first version of the context prior lowered one eval sentence ("क्या हाल है पेबल"). Picks are not a good measure of use: Pebble does chitchat at once, so you rarely pick it. The fix: count confirmed rows too, and only lift.

## Alternatives

- **The plan's formula as written.** We did not select it, for the reasons in "Context".
- **A higher τ (0.95), with no word gate.** Spread goes down to 1.2 sentences on average and 7 at most, but a paraphrase seldom reaches 0.95. The word gate is more selective for the same recall.
- **Save the example vectors in `vector_item`.** We did not select it now. `MemorySearch.reconcile` deletes vectors of other kinds, and the number of examples is small (hundreds). Do it if the refresh becomes slow.
- **Contrastive fine-tuning of the encoder (C6).** It can make the embedding group sentences by meaning, not only by intent. It is a training run, not part of C3.
