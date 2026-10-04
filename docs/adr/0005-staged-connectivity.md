# ADR 0005: Connect devices in stages, with no servers that Pebble runs

- **Status:** Accepted
- **Date:** 2026-10-04
- **Work package:** Milestone F (family circle)

## Context

Family devices must sync over the internet with no third-party cloud.
Many Indian ISPs use carrier-grade NAT (CGNAT). With CGNAT, UDP hole punching often fails.
Thus, we cannot promise a direct connection between two devices.

## Decision

Pebble will try these paths, in this sequence:
1. a direct connection on the local network (LAN);
2. the family hub, through a port forward, UPnP or Tailscale;
3. a relay on the hub;
4. hole punching, only after a spike shows that it works.

We promise two things: end-to-end encryption, and no servers that Pebble runs.

## Consequences

- The promise is honest for networks with CGNAT.
- A family that has no hub and no LAN cannot sync. The docs must say this.
- Each path needs its own test.

## Alternatives

- **Generic peer-to-peer hole punching only.** We did not select it. It often fails behind CGNAT.
- **A relay that Pebble runs.** We did not select it. It breaks the rule "no Pebble servers".
