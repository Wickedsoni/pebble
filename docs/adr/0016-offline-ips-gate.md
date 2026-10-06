# ADR 0016: Gate nudge policy changes with an offline SNIPS estimate

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** C4 (offline IPS evaluator, M3)

## Context

The nudge policy (`shared/.../brain/NudgePolicy.kt`) is a Thompson-sampling bandit.
For each repeating reminder that comes due, it selects an arm: `NOW`, `WAIT_10` or `WAIT_30`.
`ReminderEngine` logs each choice as `nudge_decided`, with the probability of that choice (the propensity).
The engine gives a reward from your reaction (`NudgePolicy.rewardFor`):
- done in 15 minutes = 1, done later = 0.6, snoozed = 0.3;
- skipped, or no reaction for 30 minutes = 0.

A change to the priors or the rewards changes what Pebble does for every user.
We cannot test the change on you live. Thus we must estimate its value from the history that we have.

The history on the maintainer's laptop (5 Oct 2026) has 42 decisions in 4 days.
Only 9 of them have a known outcome. The other 33 had an app stop or start before the reaction, because of many test builds.

## Decision

1. `NudgeIpsEvaluator` (`shared/.../brain/`) reads the raw `event_log` rows. It does not use `EventHistory`, because it pairs single entries and does not count them. It never deletes a row (ADR 0004).
2. It pairs each `nudge_decided` with the reaction, with the same rules as the engine:
   - the reminder shows at the first `reminder_due` of that key;
   - the reward comes from the first `reminder_acted` of that key in the next 30 minutes;
   - with no reaction, the reward is 0, if a later entry shows that the app ran for those 30 minutes.
3. It skips a decision when the outcome is not known. Examples: an app stop or start before the reaction (the engine forgets its decisions then), the end of the log, or a bad entry.
4. It gives three estimates of a fixed target policy: IPS, self-normalised IPS (SNIPS), and the effective sample size (ESS = (Σw)² / Σw²).
5. The reference is the mean reward of what ran. That is the on-policy value of the current policy.
6. **Gate:** a pull request that changes the priors or the rewards of `NudgePolicy` must show SNIPS ≥ the reference and ESS ≥ 200. Run `./gradlew :desktopApp:nudgeIps` and put its output in the pull request.
7. The tool copies the database with `VACUUM INTO` on a read-only connection. It does not change the database of the app.

## Consequences

- We can compare a policy change with what ran, without a live test.
- A test (`NudgeIpsEvaluatorTest`) runs the real engine and log. It shows that the evaluator finds the same rewards that the policy learned.
- On a simulated user, SNIPS finds the value of a policy that the log almost never ran (error < 0.03).
- Today, no change can pass: the ESS on the maintainer's data is 8. The gate blocks changes to the priors and rewards until the history is large enough.
- The target is the policy with today's beliefs, held fixed. The estimate does not show how a policy learns over time.
- A change to `rewardFor` also changes the reference, because the tool scores both with the new rewards. The pull request must say this.

## Alternatives

- **IPS only.** We did not select it. Its variance is high when a weight is large, for example a rare wait with a propensity of 0.05.
- **Doubly robust estimate.** We did not select it. It needs a reward model per context. With fewer than 100 decisions, that model has no value.
- **Replay of a learning policy.** We did not select it. It needs a log from a uniform random policy. Pebble never runs one, because a random nudge is bad for the user.
- **Count reactions through `EventHistory`.** We did not select it. The daily counts do not keep the pair of a decision and its reaction.

## Revision (QA round 1)

- The logged propensity now comes from 2 000 Monte-Carlo draws (before: 200). With 200 draws the standard error was about 0.035. That is too noisy for the weights 1/p. With 2 000 draws it is at most 0.011.
- The draws use a separate random source. Thus the draw count does not change which arm the policy selects (test: `theChosenArmDoesNotDependOnHowTheLoggedPropensityIsEstimated`).
- Decisions logged before this change have the noisier propensity. The evaluator uses them as they are.
