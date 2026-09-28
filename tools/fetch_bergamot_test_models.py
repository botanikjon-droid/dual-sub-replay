"""Download the Mozilla models the F-Droid device test uses into its test assets.

Usage: python3 tools/fetch_bergamot_test_models.py [output_dir]
Picks the newest release model for en->vi and vi->en from Firefox Remote Settings
and verifies every file's SHA-256 before keeping it.
"""
import hashlib
import json
import pathlib
import sys
import urllib.request

CATALOG_URL = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
ATTACHMENT_BASE_URL = "https://firefox-settings-attachments.cdn.mozilla.net/"
PAIRS = [("en", "vi"), ("vi", "en")]
DEFAULT_OUTPUT = pathlib.Path("app/build/fdroid-test-models/bergamot-test-models")


def version_key(version):
    return [int(part) if part.isdigit() else 0 for part in version.split("a")[0].split("b")[0].split(".")], version == version.split("a")[0]


def pick_files(records, source, target):
    by_version = {}
    for record in records:
        if record.get("filter_expression") or (record.get("fromLang"), record.get("toLang")) != (source, target):
            continue
        by_version.setdefault(record["version"], {})[record["fileType"]] = record["attachment"]
    complete = {
        version: files
        for version, files in by_version.items()
        if "model" in files and "lex" in files and ("vocab" in files or {"srcvocab", "trgvocab"} <= files.keys())
    }
    if not complete:
        raise SystemExit(f"No complete model for {source}-{target}")
    return complete[max(complete, key=version_key)]


def main():
    output = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUTPUT
    with urllib.request.urlopen(CATALOG_URL, timeout=60) as response:
        records = json.load(response)["data"]
    for source, target in PAIRS:
        directory = output / f"{source}-{target}"
        directory.mkdir(parents=True, exist_ok=True)
        for attachment in pick_files(records, source, target).values():
            destination = directory / attachment["filename"]
            if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == attachment["hash"]:
                continue
            with urllib.request.urlopen(ATTACHMENT_BASE_URL + attachment["location"], timeout=120) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != attachment["hash"]:
                raise SystemExit(f"Checksum mismatch for {attachment['filename']}")
            destination.write_bytes(data)
            print(f"{source}-{target}: {attachment['filename']} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
