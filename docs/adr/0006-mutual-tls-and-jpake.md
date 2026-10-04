# ADR 0006: Use mutual TLS with pinned certificates, and J-PAKE for pairing

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone F (pairing and transport)

## Context

Paired devices must trust each other with no certificate authority (CA).
Pairing must work with a short code that a person can type.
We must not write our own cryptography.

## Decision

- Each device makes a self-signed certificate.
- Devices connect with mutual TLS 1.3. Each device pins the certificate of each peer. Syncthing uses the same model.
- Pairing uses J-PAKE from BouncyCastle with the pairing code. J-PAKE gives the two devices a shared secret. The devices use this secret to exchange their certificates safely.

## Consequences

- We use the TLS of the JDK and one mature library (BouncyCastle).
- A short code is safe, because J-PAKE gives an attacker only one guess for each attempt.
- The jlink runtime can need more modules for TLS (see trap 1 in `CLAUDE.md`).
- **VERIFY** the BouncyCastle coordinates and version before you add it.

## Alternatives

- **Noise, or a PAKE that is not specified.** We did not select it. The design did not name a mature JVM library.
- **A CA for the family.** We did not select it. It adds key management that a family cannot do easily.
