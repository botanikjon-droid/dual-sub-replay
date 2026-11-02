#!/usr/bin/env python3
"""SHOKALON stage 6: checks the subtitle ZIP from the app's "SHOKALON: subtitrlarni yig‘ish" card.

It compares the archive with the catalog (app/src/main/assets/shokalon_ids.tsv) and reports:
- catalog lectures without a file, and why (manifest status);
- files whose name breaks the SHOKALON rule, whose Video ID is not in the catalog, or whose topic code does not match
  the catalog;
- two files for one lecture (duplicates);
- files that are not readable SubRip (encoding, timing lines, no cues), plus warnings for empty cues and times that
  run backwards.

Usage: python3 tools/shokalon/check_subtitle_zip.py SHOKALON-subtitrlar.zip [--out report_dir]
Writes report.md and lectures.tsv (one row per catalog lecture) to the output folder (default: next to the ZIP).
Exit status 1 when the archive has errors (not for lectures that simply have no captions).
"""

from __future__ import annotations

import argparse
import csv
import io
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from srt import SOURCE_VARIANT, SrtError, parse_name, parse_srt, read_text  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
IDS = ROOT / "app" / "src" / "main" / "assets" / "shokalon_ids.tsv"
FOLDER = "03_SUBTITLES_ORIGINAL/"
MANIFEST = "manifest.tsv"


def read_ids(path: Path = IDS) -> dict[str, dict[str, str]]:
    """Catalog lectures keyed by Video ID (V001…): {'video_id', 'code', 'vid'}."""
    ids: dict[str, dict[str, str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#") or line.startswith("video_id"):
            continue
        cells = line.split("\t")
        if len(cells) >= 3:
            ids.setdefault(cells[2], {"video_id": cells[0], "code": cells[1], "vid": cells[2]})
    return ids


@dataclass
class FileCheck:
    name: str
    vid: str = ""
    variant: str = ""
    cues: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


NOT_ENGLISH = "subtitr tili inglizcha emas"


def check_srt_bytes(data: bytes, result: FileCheck) -> None:
    try:
        cues = parse_srt(read_text(data))
    except UnicodeDecodeError:
        result.errors.append("UTF-8 emas")
        return
    except SrtError as error:
        result.errors.append(f"SRT buzilgan: {error}")
        return
    result.cues = len(cues)
    if not cues:
        result.errors.append("bitta ham subtitr bloki yo‘q")
        return
    empty = sum(1 for cue in cues if not cue.text)
    if empty:
        result.warnings.append(f"{empty} ta bo‘sh blok")
    backwards = sum(1 for cue in cues if cue.end_ms < cue.start_ms)
    if backwards:
        result.errors.append(f"{backwards} ta blokda tugash vaqti boshlanishidan oldin")


def check_zip(zip_path: Path, ids: dict[str, dict[str, str]]) -> dict:
    with zipfile.ZipFile(zip_path) as archive:
        names = [info.filename for info in archive.infolist() if not info.is_dir()]
        manifest = {}
        if MANIFEST in names:
            rows = csv.DictReader(io.StringIO(archive.read(MANIFEST).decode("utf-8-sig")), delimiter="\t")
            manifest = {row["vid"]: row for row in rows if row.get("vid")}
        files: list[FileCheck] = []
        other: list[str] = []
        for name in names:
            if name in (MANIFEST, "README.txt"):
                continue
            if not name.startswith(FOLDER) or not name.endswith(".srt"):
                other.append(name)
                continue
            result = FileCheck(name[len(FOLDER):])
            parsed = parse_name(result.name)
            if parsed is None:
                result.errors.append("nomi SHOKALON qoidasiga mos emas")
            else:
                result.vid, result.variant = parsed.vid, parsed.variant
                if not SOURCE_VARIANT.match(parsed.variant):
                    result.errors.append(f"asl subtitr papkasida '{parsed.variant}' varianti")
                if parsed.vid.startswith("V"):
                    known = ids.get(parsed.vid)
                    if known is None:
                        result.errors.append("Video ID katalogda yo‘q")
                    elif known["code"] != parsed.dotted_code:
                        result.errors.append(f"mavzu kodi {parsed.dotted_code}, katalogda {known['code']}")
                language = parsed.variant.partition("-")[2]
                if language and language != "EN":
                    result.warnings.append(f"{NOT_ENGLISH}: {language}")
            check_srt_bytes(archive.read(name), result)
            files.append(result)

    by_vid: dict[str, list[FileCheck]] = defaultdict(list)
    for result in files:
        if result.vid:
            by_vid[result.vid].append(result)
    for vid, group in by_vid.items():
        if len(group) > 1:
            for result in group:
                result.errors.append(f"{vid} uchun {len(group)} ta fayl (takroriy)")

    lectures = []
    for vid, lecture in ids.items():
        group = by_vid.get(vid, [])
        row = manifest.get(vid, {})
        if group:
            state = "fayl bor" if not any(r.errors for r in group) else "fayl xato"
            if state == "fayl bor" and any(w.startswith(NOT_ENGLISH) for r in group for w in r.warnings):
                # YouTube sometimes transcribes an English lecture as Hindi, German …: the text is then unusable.
                state = "fayl bor, inglizcha emas"
        else:
            state = {"subtitr yo‘q": "subtitr yo‘q", "xato": "yuklanmadi (xato)"}.get(row.get("status", ""), "fayl yo‘q")
            if row.get("status") == "bor":
                state = "manifestda bor, ZIPda yo‘q"
        lectures.append(
            {
                "vid": vid,
                "code": lecture["code"],
                "video_id": lecture["video_id"],
                "state": state,
                "file": "; ".join(r.name for r in group),
                "variant": "; ".join(r.variant for r in group),
                "cues": sum(r.cues for r in group),
                "note": "; ".join(e for r in group for e in r.errors + r.warnings) or row.get("note", ""),
            }
        )
    return {"files": files, "other": other, "lectures": lectures, "has_manifest": bool(manifest)}


def write_report(result: dict, out: Path, zip_name: str) -> int:
    out.mkdir(parents=True, exist_ok=True)
    lectures, files = result["lectures"], result["files"]
    with (out / "lectures.tsv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(lectures[0].keys()) if lectures else ["vid"], delimiter="\t")
        writer.writeheader()
        writer.writerows(lectures)
    states = Counter(row["state"] for row in lectures)
    variants = Counter(r.variant.split("-")[0] for r in files if r.variant)
    bad = [r for r in files if r.errors]
    outside = [r for r in files if r.vid.startswith("YT-")]
    errors = len(bad) + len(result["other"]) + (0 if result["has_manifest"] else 1)
    lines = [
        f"# SHOKALON subtitr ZIP tekshiruvi: {zip_name}",
        "",
        "| Ko‘rsatkich | Soni |",
        "| --- | --- |",
        f"| Katalogdagi darslar | {len(lectures)} |",
        f"| ZIPdagi subtitr fayllari | {len(files)} |",
    ]
    lines += [f"| Darslar: {state} | {count} |" for state, count in sorted(states.items())]
    lines += [
        f"| Muallif subtitri (ORIGINAL) | {variants.get('ORIGINAL', 0)} |",
        f"| YouTube avtomatik subtitri (ASR) | {variants.get('ASR', 0)} |",
        f"| Katalogdan tashqari (shifokor qo‘shgan) | {len(outside)} |",
        f"| Xatoli fayllar | {len(bad)} |",
        "",
    ]
    if not result["has_manifest"]:
        lines += ["**Xato:** ZIP ichida manifest.tsv yo‘q.", ""]
    if result["other"]:
        lines += ["## Kutilmagan fayllar", ""] + [f"- `{name}`" for name in result["other"]] + [""]
    if bad:
        lines += ["## Xatoli fayllar", "", "| Fayl | Muammo |", "| --- | --- |"]
        lines += [f"| `{r.name}` | {'; '.join(r.errors)} |" for r in bad] + [""]
    missing = [row for row in lectures if row["state"] != "fayl bor"]
    if missing:
        lines += ["## Subtitri yo‘q yoki xatoli darslar", "", "| Video ID | Kod | YouTube | Holat |", "| --- | --- | --- | --- |"]
        lines += [
            f"| {row['vid']} | {row['code']} | https://www.youtube.com/watch?v={row['video_id']} | {row['state']} |"
            for row in missing
        ] + [""]
    lines += ["To‘liq ro‘yxat: `lectures.tsv`.", ""]
    (out / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("zip", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--ids", type=Path, default=IDS)
    args = parser.parse_args(argv)
    result = check_zip(args.zip, read_ids(args.ids))
    out = args.out or args.zip.with_suffix("")
    errors = write_report(result, out, args.zip.name)
    print((out / "report.md").read_text(encoding="utf-8"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
