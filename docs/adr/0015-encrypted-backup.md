# ADR 0015: Encrypt backups in 1 MiB AES-GCM chunks, and restore them at the next start

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** E4 (encrypted backup and restore)

## Context

Before E4, Pebble had no backup. A lost or replaced computer meant lost notes, reminders, calendar and memory. The plan asks for an export to a file that is encrypted with a passphrase: SQLite `VACUUM INTO`, then AES-256-GCM with a key from PBKDF2-HMAC-SHA256 (600 000 iterations or more, a random salt), and a header with the version, salt and nonce. An import checks the file, then replaces the database at the next start.

Facts:
- The app has a 256 MB heap (`-Xmx256m`). A database with a long event log can be tens of MB. The JDK's AES-GCM decryption keeps the full ciphertext in memory until `doFinal`.
- The database runs in WAL mode (trap 11), so a file copy can miss recent changes.
- The JDK has AES-GCM and PBKDF2 in `java.base` (SunJCE), so the jlink image needs no new module. `java.sql` is already in it.
- 600 000 PBKDF2 iterations took 369 ms on the developer's laptop (`BackupTest`).

## Decision

1. **Format (`BackupFile`):** a 36-byte header (`PEBBLEBK`, version 1, iterations, 16-byte salt, 7-byte nonce prefix), then the data in **chunks of 1 MiB**, each one AES-256-GCM with a 16-byte tag.
   - Nonce of chunk i = prefix | i | a "last chunk" flag (the STREAM construction). A cut, added or moved chunk fails its tag, also when a file is cut at a chunk boundary.
   - Each chunk authenticates the header, so a changed salt or iteration count also fails.
   - This is a difference from the plan (one nonce, one GCM message). It keeps memory near 2 MiB for any database size.
2. **Export:** `eventLog.flush()`, then `VACUUM INTO` a temp file in the data folder (a consistent copy, WAL included), then encrypt it to `<file>.partial` and rename it. The plain temp copy is always deleted.
3. **Restore:** decrypt to a temp file, check it (`integrity_check` ok, `user_version` from 1 to this app's schema version, a `setting` table), and keep it as `pebble.db.restore`. At the next start, before Pebble opens the database, `Backup.applyStaged` renames the current files to `pebble.db.before-restore` and moves the restore in. The migrations then upgrade an older backup. You can cancel a staged restore.
4. **Device id:** the restored database gets this computer's `device.id`. When you restore a backup on a new computer, the two computers do not get the same id (milestone F). Rows keep their `origin_device`.
5. **Passphrase:** 8 characters or more. The card asks for it two times for an export. The state holder wipes the `CharArray` after use. Pebble does not keep the passphrase, and nobody can recover a forgotten one.
6. **UI:** a "Backup" card on the About page (`BackupStateHolder`). The file name ends in `.pebblebackup`.

## Consequences

- One file holds everything: notes, reminders, calendar, memory, conversation, event log and settings. It does not hold voice clip audio files or model packs.
- The Compose text field keeps the passphrase as a `String` until the card clears the field. A memory dump of the running app could show it. This is the same as other desktop apps; the file on disk is the asset to protect.
- A downgrade is refused: a backup from a newer Pebble is not staged.

## Alternatives

- **One AES-GCM message for the whole file (the plan's format).** We did not select it. The JDK buffers the full ciphertext when it decrypts, which can pass the 256 MB heap on a large database.
- **Argon2 instead of PBKDF2.** We did not select it. It needs a new dependency (BouncyCastle), and the plan names PBKDF2.
- **Replace the database while Pebble runs.** We did not select it. Open connections, the event writer and the WAL file make a live swap unsafe. A restart is simple and safe.
