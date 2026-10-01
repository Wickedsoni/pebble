"""Downloads Amazon MASSIVE 1.1 (en-US + hi-IN) into brain/data/raw/massive/.

Run:  uv run python data/download_massive.py

Only the two locales Pebble needs are extracted. The script stops if the licence bundled in the
archive is not CC BY 4.0, so we never silently train on data we can't ship.
"""

import io
import json
import pathlib
import tarfile
import urllib.request

URL = "https://amazon-massive-nlu-dataset.s3.amazonaws.com/amazon-massive-dataset-1.1.tar.gz"
LOCALES = ("en-US", "hi-IN")
ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "raw" / "massive"


def main() -> None:
    if all((OUT / f"{loc}.jsonl").exists() for loc in LOCALES):
        print("MASSIVE already downloaded:", OUT)
        return
    print("Downloading", URL)
    data = urllib.request.urlopen(URL, timeout=120).read()
    print(f"  {len(data) / 2**20:.1f} MB")

    OUT.mkdir(parents=True, exist_ok=True)
    licence_text = None
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar.getmembers():
            name = pathlib.PurePosixPath(member.name).name
            if name == "LICENSE":
                licence_text = tar.extractfile(member).read().decode("utf-8", "replace")
            elif name in {f"{loc}.jsonl" for loc in LOCALES}:
                (OUT / name).write_bytes(tar.extractfile(member).read())
                print("  extracted", name)

    if not licence_text or "Attribution 4.0" not in licence_text:
        raise SystemExit("Licence check failed: expected CC BY 4.0 in the archive's LICENSE. Not using this data.")
    (OUT / "LICENSE").write_text(licence_text, encoding="utf-8")

    # Record the verification in the licence manifest.
    manifest_path = ROOT / "licenses.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for d in manifest["datasets"]:
        if d["name"].startswith("Amazon MASSIVE"):
            d["verified"] = True
            d["verified_by"] = "LICENSE file inside amazon-massive-dataset-1.1.tar.gz contains 'Attribution 4.0'"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("Licence verified (CC BY 4.0) and recorded in licenses.json")


if __name__ == "__main__":
    main()
