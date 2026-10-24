#!/usr/bin/env python3
"""SHOKALON stage 8 → 9: checks a batch of Uzbek translations against the originals and packs the good ones.

Usage:
  python3 tools/shokalon/package_translations.py SOURCE_DIR TRANSLATED_DIR --scope 01 --out OUT_DIR [--notes NOTES.tsv]

--scope is the start of the topic code without dots (01 = section I, 0101 = chapter 1); the batch is every original
subtitle in that scope. Writes to OUT_DIR:
- SHOKALON_04_SUBTITLES_TRANSLATED_{scope}.zip: 04_SUBTITLES_TRANSLATED/SHK_{code}_{vid}_UZ-LATIN.srt for every
  translation without errors (file names keep the original code and Video ID), plus HISOBOT.md;
- HISOBOT.md: counts, files not translated, files held back for errors, warnings, and the translator's notes on
  doubtful places (NOTES.tsv: vid, blok, asl, tarjima, sabab, ishonch). Notes never go into the SRT files.
Exit status 1 when a translation has errors.
"""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_translation import check_pair, pairs, read_glossary  # noqa: E402
from srt import SOURCE_VARIANT, parse_name, parse_srt, read_text  # noqa: E402

FOLDER = "04_SUBTITLES_TRANSLATED/"


def in_scope(code: str, scope: str) -> bool:
    return code.startswith(scope)


def read_notes(path: Path | None) -> list[dict[str, str]]:
    if path is None or not path.is_file():
        return []
    return list(csv.DictReader(path.read_text(encoding="utf-8").splitlines(), delimiter="\t"))


def package(source_dir: Path, translated_dir: Path, scope: str, out: Path, notes: Path | None = None) -> dict:
    glossary = read_glossary()
    sources = {}
    for path in sorted(source_dir.glob("*.srt")):
        name = parse_name(path.name)
        if name and SOURCE_VARIANT.match(name.variant) and in_scope(name.code, scope):
            sources[name.vid] = path
    matched, orphans = pairs(source_dir, translated_dir)
    matched = [(src, uz) for src, uz in matched if parse_name(src.name).vid in sources]
    done = {parse_name(src.name).vid for src, _ in matched}
    missing = [path.name for vid, path in sources.items() if vid not in done]
    reports = [(src, uz, check_pair(src, uz, glossary)) for src, uz in matched]
    good = [(src, uz, r) for src, uz, r in reports if not r.errors]
    bad = [(src, uz, r) for src, uz, r in reports if r.errors]
    cues = sum(len(parse_srt(read_text(src.read_bytes()))) for src, _, _ in good)
    vids = {parse_name(src.name).vid for src, _, _ in reports}
    note_rows = [row for row in read_notes(notes) if row.get("vid") in vids]

    out.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# SHOKALON tarjima hisoboti: {scope} doirasi",
        "",
        "| Ko‘rsatkich | Soni |",
        "| --- | --- |",
        f"| Asl subtitr fayllari (doirada) | {len(sources)} |",
        f"| Tarjima qilingan | {len(reports)} |",
        f"| Tekshiruvdan o‘tgan, ZIPga kirgan | {len(good)} |",
        f"| Xato bilan qaytarilgan (ZIPga kirmagan) | {len(bad)} |",
        f"| Hali tarjima qilinmagan | {len(missing)} |",
        f"| ZIPdagi subtitr bloklari | {cues} |",
        f"| Ogohlantirishlar (jami) | {sum(len(r.warnings) for _, _, r in reports)} |",
        f"| Shubhali joylar izohi | {len(note_rows)} |",
        "",
        "Tekshiruv har bir faylni asl fayl bilan solishtiradi: bloklar soni, raqamlari va vaqt kodlari, bo‘sh yoki",
        "tarjima qilinmagan blok, sonlar, birliklar, takrorlangan blok, SRT ichidagi izoh belgilari, lug‘at atamalari,",
        "o‘/g‘ belgilari, qator uzunligi. Avtomatik tekshiruv tibbiy to‘g‘rilikni isbotlamaydi.",
        "",
        "## Fayllar",
        "",
        "| Asl fayl | Tarjima | Bloklar | Xato | Ogohlantirish |",
        "| --- | --- | --- | --- | --- |",
    ]
    for src, uz, report in reports:
        count = len(parse_srt(read_text(src.read_bytes())))
        lines.append(f"| `{src.name}` | `{uz.name}` | {count} | {len(report.errors)} | {len(report.warnings)} |")
    if bad:
        lines += ["", "## Xatolar (bu fayllar ZIPga kirmadi)", ""]
        for _, uz, report in bad:
            lines += [f"- `{uz.name}`: " + "; ".join(report.errors[:10])]
    if missing:
        lines += ["", "## Hali tarjima qilinmagan", ""] + [f"- `{name}`" for name in missing]
    if orphans:
        lines += ["", "## Asl fayli topilmagan tarjimalar", ""] + [f"- `{name}`" for name in orphans]
    warned = [(uz, report) for _, uz, report in reports if report.warnings]
    if warned:
        kinds = Counter(w.split(": ", 1)[-1].split(":")[0].split(" (")[0] for _, r in warned for w in r.warnings)
        lines += ["", "## Ogohlantirishlar", "", "| Tur | Soni |", "| --- | --- |"]
        lines += [f"| {kind} | {count} |" for kind, count in kinds.most_common()]
        lines += [""]
        for uz, report in warned:
            lines += [f"- `{uz.name}`: " + "; ".join(report.warnings[:15]) + (" …" if len(report.warnings) > 15 else "")]
    if note_rows:
        lines += [
            "",
            "## Shubhali va tuzatilgan joylar (tarjimon izohi)",
            "",
            "| Video ID | Blok | Asl matn | Tarjima | Sabab | Ishonchlilik |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
        lines += [
            f"| {r.get('vid', '')} | {r.get('blok', '')} | {r.get('asl', '')} | {r.get('tarjima', '')} | {r.get('sabab', '')} | {r.get('ishonch', '')} |"
            for r in note_rows
        ]
    text = "\n".join(lines) + "\n"
    (out / "HISOBOT.md").write_text(text, encoding="utf-8")
    archive = out / f"SHOKALON_04_SUBTITLES_TRANSLATED_{scope}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as handle:
        for _, uz, _ in good:
            handle.write(uz, FOLDER + uz.name)
        handle.writestr("HISOBOT.md", text)
    return {"sources": len(sources), "translated": len(reports), "good": len(good), "bad": len(bad), "missing": missing, "zip": archive}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("translated_dir", type=Path)
    parser.add_argument("--scope", default="")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args(argv)
    result = package(args.source_dir, args.translated_dir, args.scope, args.out, args.notes)
    print({key: value for key, value in result.items() if key != "missing"}, f"missing={len(result['missing'])}")
    return 1 if result["bad"] else 0


if __name__ == "__main__":
    sys.exit(main())
