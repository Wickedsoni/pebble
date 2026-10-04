# ADR 0007: Run the chat model as a llama-server sidecar process

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone C (local chat, M5)

## Context

The chat model (for example, Qwen2.5-0.5B) uses llama.cpp.
The JVM of Pebble runs with `-Xmx256m` (`desktopApp/build.gradle.kts:110`).
A crash in native code inside the JVM stops all of Pebble.
Pebble unloads each model after 10 idle minutes.

## Decision

Pebble will start `llama-server` as a separate process that listens on `127.0.0.1` only.
Pebble will send requests to it over HTTP.
To unload the chat model, Pebble stops the process.

## Consequences

- A crash of the chat model does not stop Pebble.
- The idle unload is simple: stop the process.
- The memory of the chat model is not in the 256 MB heap limit.
- The hub can use the same binary.
- Pebble must ship and update one more binary. The HTTP client can need a jlink module (trap 1 in `CLAUDE.md`).
- **VERIFY** the `llama-server` flags for the pinned llama.cpp version.

## Alternatives

- **llama.cpp through JNI.** We did not select it. A native crash stops the app, and the unload is more difficult.
