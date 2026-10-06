# ADR 0011: Load models from the user folder only from signed packs

- **Status:** Accepted
- **Date:** 2026-10-05
- **Work package:** D2 (signed model packs, lite/full installers). This ADR is the review of the signing design that the plan asks for.

## Context

Before D2, Pebble loaded a model from `%APPDATA%\Pebble\models\<name>` first, before the bundled model.
`ModelChecksums.verify` trusted a folder with no `manifest.json` entry ("hand-placed model is allowed").
Thus any program that can write to the user profile could replace the command model or the speech models. A model file is code-like input to ONNX Runtime and sherpa-onnx.

The speech models are about 300 MB of the 364 MB installer. A smaller installer needs a safe way to add them later.

## Decision

1. **Pack format.** A pack is a zip with the model files, `pack.json` and `pack.sig`.
   - `pack.json` has `format` (1), `name` (`intent` or `asr`), `version`, `minApp`, `keyId`, and `files` (path, size, SHA-256).
   - `pack.sig` is the Ed25519 signature (Base64) of the **exact bytes** of `pack.json`. We do not serialise again before we check, so there is no canonical-JSON problem.
2. **Trust.** The app has a map of key id → public key (`ModelPack.TRUSTED_KEYS`). Today there is one key, `pebble-2026a`. A new key gets a new id. The app can trust two keys during a change.
3. **Install** (About → Model packs → install from a file):
   - Check the signature first. Then check the name, `minApp` and the total size (2 GB at most).
   - Unpack only the files that `pack.json` lists. Each path must match `[A-Za-z0-9_.-]` segments with no `..`, so no file can go out of the model folder ("zip slip").
   - Copy each file with a byte limit equal to its declared size, and hash it while it is copied. The size field of the zip is not trusted.
   - Unpack to a temporary folder, then move it to `<name>.staged`. The install is done at the next start, before any model loads, because Windows can lock the files of a loaded model. Removal is staged the same way.
4. **Load.** Pebble uses the user folder only if `ModelPack.verifyInstalled` passes at load time: the signature, the model name and every file hash. `VerifiedModelCache` keeps this fast. If the check fails, Pebble uses the bundled model.
   - The folder must hold no file that `pack.json` does not list. The signature does not cover such a file, and a program (the chat server) can load it. Only `desktop.ini` and `Thumbs.db` are ignored, because Windows Explorer adds them.
   - For the chat pack, the model file comes from the list in `pack.json`, not from a folder listing.
   - The check runs off the UI thread. A failed load is not tried again until a model file changes or 5 minutes pass.
5. **Developers.** `PEBBLE_MODELS_DIR`, `PEBBLE_ASR_DIR` and `brain/models` are not checked against a signature (as before).
6. **Key custody.** The private key is a file on the maintainer's computer: `%USERPROFILE%\.pebble\signing\pebble-2026a.key`. It is never in the repo or in CI. The maintainer signs packs with `./gradlew :desktopApp:modelPack --args="sign …"`.
7. **Lite installer.** `-Pflavor=lite` leaves the speech models out of `stageModel`. The release workflow builds `Pebble-x.y.z.msi` and `Pebble-lite-x.y.z.msi`.
8. **Runtime module.** Ed25519 is in `jdk.crypto.ec` on JDK 21 (in `java.base` only from JDK 22). We name it in `modules(...)`. The built runtime lists it (`runtime/release`, checked 2026-10-05).

## Consequences

- A model that someone put in the user folder by hand, or changed after the install, is not loaded.
- **If the private key is lost**, no new pack can be signed for the apps that are installed. A new app release with a new key is then necessary. Keep an offline copy of the key file.
- **If the private key leaks**, anyone can sign models that Pebble loads. Ship an app release that removes the key id from `TRUSTED_KEYS`.
- An older pack with a valid signature can be installed again (no rollback protection). The user selects the file, and the version shows on the About page. We accept this for a local, offline install.
- The installers themselves are not signed yet (code signing is an open item for the user).

## Alternatives

- **SHA-256 list only (no signature).** We did not select it. Whoever can write the model can also write the list.
- **Sign in CI with a GitHub secret.** We did not select it now. The plan keeps the key offline. A CI key is easier but has a larger attack surface.
- **Minisign / signify files.** Same Ed25519, but a new format and a new dependency. The JDK API is sufficient.
- **Replace the files at once, with no staging.** We did not select it. A loaded model can lock its files on Windows, and a half-replaced folder could then load.
