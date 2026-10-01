# Next session — where we stopped

## State
- M1 command model trained, pruned (31 MB int8), and running in Kotlin with exact Python parity (`OnnxParityTest`).
- Router (rules → model → "Did you mean…?"), Hinglish time reader, feedback store and quick-add UI wired in.
- This commit is **WIP**: the parser fixes below were mid-edit. Run `./gradlew :shared:jvmTest :desktopApp:test` first.

## Finish first (bugs found by `RouterWithModelTest`)
1. `QuickAddParser.kt`: check that `harRx`, `hiWaterRx` and `enWaterRx` contain real `\b` word boundaries, not stray
   backspace (0x08) characters left by a scripted edit (`grep -c $'\x08' shared/.../QuickAddParser.kt` must be 0).
2. Update the `QuickAddParserTest` expectation: "standup today at 9:15" is now `RemindAt(..., flexibleHalfDay = true)`.
3. `CommandRouter.reminder()`: if the slot text has no clock (`HinglishTime.hasClock`), parse the full sentence instead
   ("shaam 7 baje" currently gives 6 PM).
4. `CommandRouter.reminderTitle()`: keep every non-time slot word (drop only time/date/timeofday tags and filler); when
   the title is empty use "Wake up" (alarm_set) or "Reminder", never the whole sentence.
5. `Replies`: low-mood words (mood off, low, sad, udaas, उदास) get a caring reply instead of a joke.
6. Re-run `RouterWithModelTest` and check the eval-sentence table, then launch the app and try quick add (Ctrl+Alt+Space).
7. Commit.

## Then (from the plan)
- Replace the draft eval set with ~50 of your real commands.
- M2: semantic memory search + few-shot prototypes (log water, remember, mood).
- Parallel track: Claude Code task delegation with pet status.
