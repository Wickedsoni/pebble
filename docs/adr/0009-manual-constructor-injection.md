# ADR 0009: Use manual constructor injection with one composition root

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone B (`AppGraph`)

## Context

`PebbleApp` makes its own objects and scopes in field initialisers (`PebbleApp.kt:93,105,110-111`).
Tests must replace the clock, the dispatchers and the UI hooks.
The object graph is small.

## Decision

We will use manual constructor injection.
One composition root, `AppGraph`, will make all objects.
`AppGraph` will own `AppScope`, `DispatcherProvider`, `Clock`, `UiPort` and `Logger`.

## Consequences

- Each dependency is a constructor parameter, so tests can replace it.
- No framework API exists that an implementer can guess incorrectly.
- Each new service needs a manual change in `AppGraph`.

## Alternatives

- **Koin.** We did not select it. The graph is small. A framework adds an API to learn and runtime errors that the compiler cannot find.
