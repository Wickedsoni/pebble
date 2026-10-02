# Security policy

Pebble runs locally and handles personal data: notes, reminders, habits and (opt-in) voice clips. We take that seriously.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through
[GitHub private vulnerability reporting](https://github.com/Wickedsoni/pebble/security/advisories/new).

Include what you found, how to reproduce it and the impact you see. You'll get a reply within 7 days. We'll keep you updated until it's fixed and credit you in the release notes, unless you'd rather stay anonymous.

## In scope

- Anything that sends user data off the machine, or lets another local user or app read it.
- The microphone being active outside push-to-talk.
- Loading a tampered model file (models are checked against SHA-256 in `brain/models/manifest.json`).
- Code execution through model files, settings or crafted input to Quick Add.

## Supported versions

Only the latest release and `main` get security fixes.
