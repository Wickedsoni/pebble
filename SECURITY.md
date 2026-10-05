# Security policy

Pebble runs locally and handles personal data: notes, reminders, habits and (opt-in) voice clips. We take that seriously.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through
[GitHub private vulnerability reporting](https://github.com/Wickedsoni/pebble/security/advisories/new).

Include what you found, how to reproduce it and the impact you see. You'll get a reply within 7 days. We'll keep you updated until it's fixed and credit you in the release notes, unless you'd rather stay anonymous.

## In scope

- Anything that sends user data off the machine, or lets another local user or app read it.
- The microphone being active outside push-to-talk.
- Loading a tampered model file. Bundled models are checked against SHA-256 in `brain/models/manifest.json`. Models in `%APPDATA%\Pebble\models` load only from a model pack signed by the Pebble project (Ed25519, see `docs/adr/0011-signed-model-packs.md`).
- Code execution through model files, settings or crafted input to Quick Add.
- Backup files (About → Backup): AES-256-GCM in 1 MiB chunks, key from PBKDF2-HMAC-SHA256 (600 000 iterations, random salt). A changed, cut or reordered file must be refused, and a wrong passphrase must never give data (`docs/adr/0015-encrypted-backup.md`). The plain copy made during an export must never stay on disk.
- The local chat server (Smart replies, off by default): it must listen only on 127.0.0.1, refuse requests without the per-start key, and never reach the internet. Its program comes only from a signed chat pack.

## Model signing key

- Model packs are signed with an Ed25519 key. The app trusts the public keys in `ModelPack.TRUSTED_KEYS` (key id `pebble-2026a`).
- The private key is kept offline by the maintainer. It is never in this repository or in CI.
- If a signing key is lost or exposed, a new app release removes its key id and adds a new key. Report a suspected key exposure through private vulnerability reporting.

## Supported versions

Only the latest release and `main` get security fixes.
