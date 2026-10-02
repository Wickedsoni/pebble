"""Zips released models for GitHub Releases and records them in models/manifest.json (maintainers).

Run:  uv run python -m pebble_brain.package_models models-2026.10

For each manifest entry: zips exactly the files it lists (paths kept, so the zip unpacks into
models/), writes dist/<folder>.zip, and sets entry["download"] = {url, sha256, bytes} for the given
release tag. Then:  gh release create <tag> brain/models/dist/*.zip   and commit the manifest.
download_models.py is the other half.
"""

from __future__ import annotations

import json
import pathlib
import sys
import zipfile

from .download_models import sha256

MODELS = pathlib.Path(__file__).resolve().parents[2] / "models"
REPO = "Wickedsoni/pebble"


def package(tag: str, root: pathlib.Path = MODELS) -> None:
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    for entry in manifest["models"]:
        folder = next(iter(entry["files"].values()))["path"].split("/")[0]
        zpath = dist / f"{folder}.zip"
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
            for f in entry["files"].values():
                if sha256(root / f["path"]) != f["sha256"]:
                    raise SystemExit(f"{f['path']} doesn't match the manifest — refresh it first")
                z.write(root / f["path"], f["path"])
        entry["download"] = {
            "url": f"https://github.com/{REPO}/releases/download/{tag}/{zpath.name}",
            "sha256": sha256(zpath),
            "bytes": zpath.stat().st_size,
        }
        print(f"{zpath.name}: {zpath.stat().st_size / 2**20:.0f} MB")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    package(sys.argv[1])
