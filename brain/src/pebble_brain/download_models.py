"""Downloads the released models listed in models/manifest.json and verifies every file.

Run:  python -m pebble_brain.download_models          (from brain/, any Python 3.10+; stdlib only)
      python brain/src/pebble_brain/download_models.py  (from the repo root, e.g. in CI)

For contributors who want the full brain without training it, and for the release build. Each manifest
entry has a "download" {url, sha256} pointing at a zip on GitHub Releases; the zip unpacks to the
folder the entry's file paths use. Every file is checked against its SHA-256 before it is trusted.
Files already present and correct are left alone.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile

MODELS = pathlib.Path(__file__).resolve().parents[2] / "models"
#: Seconds without data before a download stops (a stalled connection must not hang a CI job).
TIMEOUT_SECONDS = 60


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def files_ok(entry: dict, root: pathlib.Path) -> bool:
    return all((root / f["path"]).exists() and sha256(root / f["path"]) == f["sha256"] for f in entry["files"].values())


def fetch(entry: dict, root: pathlib.Path = MODELS) -> None:
    name = f"{entry['name']} {entry['version']}"
    if files_ok(entry, root):
        print(f"{name}: already here, checksums match")
        return
    dl = entry.get("download")
    if not dl:
        raise SystemExit(f"{name}: missing and no download URL in the manifest (build it with the brain pipeline)")
    with tempfile.TemporaryDirectory() as tmp:
        zpath = pathlib.Path(tmp) / "model.zip"
        print(f"{name}: downloading {dl['url']}")
        with urllib.request.urlopen(dl["url"], timeout=TIMEOUT_SECONDS) as r, zpath.open("wb") as out:
            shutil.copyfileobj(r, out)
        if sha256(zpath) != dl["sha256"]:
            raise SystemExit(f"{name}: download checksum mismatch — refusing to use it")
        with zipfile.ZipFile(zpath) as z:
            for member in z.namelist():  # no path tricks: everything must stay inside models/
                if pathlib.PurePosixPath(member).is_absolute() or ".." in pathlib.PurePosixPath(member).parts:
                    raise SystemExit(f"{name}: unsafe path in zip: {member}")
            z.extractall(root)
    if not files_ok(entry, root):
        raise SystemExit(f"{name}: unpacked files don't match the manifest checksums")
    print(f"{name}: ok")


def main(root: pathlib.Path = MODELS) -> None:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["models"]:
        fetch(entry, root)


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else MODELS)
