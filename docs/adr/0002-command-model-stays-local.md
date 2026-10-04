# ADR 0002: Keep the command model on the device

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone F (home hub)

## Context

The command model reads one sentence in about 4–5 ms on the CPU of the laptop.
A family hub can run larger models for weak laptops.
A network round trip to the hub takes more time than the command model.
Pebble must also work with no network.

## Decision

The command model will always run on the device. It will never run on the hub.
Only the chat model (tier 3) can run on the hub. The speech model can also run on the hub, as an option.

## Consequences

- Commands stay fast and work offline.
- The hub does not see each command that you type or say.
- Each device must keep a copy of the command model (31 MB).

## Alternatives

- **Run the full brain on the hub.** We did not select it. The round trip is slower than the local model, and commands fail when the network is down.
