# ADR 0008: Extract a module only when a second consumer exists

- **Status:** Accepted (revised in WP E3a: `:sync` waits for `:hub`)
- **Date:** 2026-10-04
- **Work package:** E3 (`:sync`), F5 (`:brain-runtime`, `:hub`)

## Context

The repo has two modules: `:shared` and `:desktopApp` (`settings.gradle.kts`).
The target design has more modules: `:sync`, `:brain-runtime` and `:hub`.
A module with only one consumer adds build cost and gives no value.

## Decision

We will extract a module only when a second consumer needs the code:
- `:sync` at WP F5, with `:hub` (revised in E3a: at E3 the journal had to be written in the transactions of the repositories in `:shared`, so `:sync` would have had one consumer; see `docs/specs/E3-CHANGE-JOURNAL.md`, D1);
- `:brain-runtime` and `:hub` at WP F5.

## Consequences

- The build stays small until the extra modules are necessary.
- Package names must stay clean now, so that a later extraction is easy.

## Alternatives

- **Split all modules now.** We did not select it. It adds structure before a need exists, and it makes each early WP larger.
