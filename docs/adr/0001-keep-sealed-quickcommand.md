# ADR 0001: Keep the sealed QuickCommand and return undo as a value

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone B refactor (`CommandExecutor`)

## Context

`PebbleApp.execute` runs each `QuickCommand` in one exhaustive `when`.
`PebbleApp` keeps the last undo action in a shared mutable field, `private var lastUndo` (`PebbleApp.kt:245`).
`execute` sets this field, and `executeFromModel` reads it (`PebbleApp.kt:245-263, 266-336`).
If two commands run close together, one command can read the undo action of the other command.

## Decision

We will keep the sealed `QuickCommand` type and the exhaustive `when`.
We will move the `when` into a new `CommandExecutor` class.
`CommandExecutor` will return a value `Executed(line, undo)`.
The caller keeps the undo action of its own command. No shared field holds it.

## Consequences

- The compiler continues to find each command that has no handler.
- The race on `lastUndo` stops, because each call gets its own undo action.
- Tests can check the undo action directly from the return value.
- A new command still needs a change in the `when`. This is the intended cost.

## Alternatives

- **A `CommandHandler` registry (a map from command type to handler).** We did not select it. A registry loses the exhaustiveness check of the compiler. It also does not fix the shared undo field.
