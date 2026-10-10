#!/usr/bin/env python3
"""Convert the doctor-reviewed UZI glossary workbook into the app's TSV asset.

Usage:
    python3 tools/glossary/convert_uzi_glossary.py \
        docs/glossary/UZI_atamalari_tekshirilgan.xlsx \
        app/src/main/assets/uzi_glossary.tsv

The workbook's "Atamalar" sheet has the columns
A No | B category | C English term | D other forms | E Uzbek draft | F note draft |
G status | H corrected Uzbek | I corrected note | J general word (Ha/Yo'q).

Rules (the same ones the doctor was given on the "Yo'riqnoma" sheet):
  * "To'g'ri"       -> keep E and F (approved).
  * "Tuzatildi"     -> use H and I; an empty H or I keeps E or F (never blanks a value).
  * "O'chirish"     -> left out.
  * "Yangi"         -> added like an approved row (A-F filled by the doctor; H/I override).
  * "Tekshirilmagan" or anything else -> left out of the approved glossary and listed.

Only the typography of the Uzbek apostrophes is unified (o' g' -> o‘ g‘, the glottal stop
-> ’); no word is changed. openpyxl is needed only for this conversion, not by the app.
"""

from __future__ import annotations

import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

SHEET = "Atamalar"
FORMAT_VERSION = 1
HEADER = ["id", "category", "english", "aliases", "uzbek", "note", "status", "general"]

APOSTROPHES = "'‘’ʼʻʹ′`´＇"
_OKINA_AFTER_O_G = re.compile(f"([oOgG])[{re.escape(APOSTROPHES)}]")
_GLOTTAL = re.compile(f"(?<=[^\\W\\d_])(?<![oOgG])[{re.escape(APOSTROPHES)}](?=[^\\W\\d_])")


def norm_status(value: object) -> str:
    """'To'g'ri', 'To‘g‘ri' and 'TO'G'RI' are one status."""
    text = unicodedata.normalize("NFC", str(value or "")).strip().lower()
    for mark in APOSTROPHES:
        text = text.replace(mark, "'")
    return text


APPROVED = "to'g'ri"
CORRECTED = "tuzatildi"
DELETE = "o'chirish"
NEW = "yangi"


def uzbek_typography(text: str) -> str:
    """o' g' with any apostrophe look-alike -> o‘ g‘; the remaining glottal stop -> ’."""
    text = unicodedata.normalize("NFC", text.strip())
    text = _OKINA_AFTER_O_G.sub(lambda m: m.group(1) + "‘", text)
    return _GLOTTAL.sub("’", text)


def cell(value: object) -> str:
    """One-line TSV-safe text."""
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def is_general(value: object) -> bool:
    return norm_status(value) == "ha"


@dataclass
class Entry:
    id: int
    category: str
    english: str
    aliases: list[str]
    uzbek: str
    note: str
    status: str  # "approved" | "corrected" | "new"
    general: bool


@dataclass
class Result:
    entries: list[Entry]
    total_rows: int
    status_counts: dict[str, int]
    skipped: list[str]
    problems: list[str]


def convert_rows(rows: list[tuple]) -> Result:
    entries: list[Entry] = []
    counts: dict[str, int] = {}
    skipped: list[str] = []
    problems: list[str] = []
    total = 0
    for row in rows:
        row = tuple(row) + (None,) * (10 - len(row))
        number, category, english, other, uz_draft, note_draft, status, uz_fix, note_fix, general = row[:10]
        if not any(cell(v) for v in row[:10]):
            continue
        total += 1
        key = norm_status(status)
        counts[cell(status) or "(bo'sh)"] = counts.get(cell(status) or "(bo'sh)", 0) + 1
        label = f"#{cell(number)} {cell(english)}"
        if key == DELETE:
            skipped.append(f"{label}: O'chirish")
            continue
        if key == CORRECTED:
            uzbek = cell(uz_fix) or cell(uz_draft)
            note = cell(note_fix) or cell(note_draft)
            kind = "corrected"
        elif key == APPROVED:
            uzbek, note, kind = cell(uz_draft), cell(note_draft), "approved"
        elif key == NEW:
            uzbek = cell(uz_fix) or cell(uz_draft)
            note = cell(note_fix) or cell(note_draft)
            kind = "new"
        else:
            skipped.append(f"{label}: tasdiqlanmagan holat '{cell(status)}'")
            continue
        if not cell(english) or not uzbek:
            problems.append(f"{label}: inglizcha atama yoki o'zbekcha tarjima bo'sh")
            continue
        try:
            entry_id = int(number)
        except (TypeError, ValueError):
            problems.append(f"{label}: № raqam emas")
            continue
        aliases = [cell(a) for a in re.split(r";", cell(other)) if cell(a)]
        entries.append(
            Entry(
                id=entry_id,
                category=uzbek_typography(cell(category)),
                english=cell(english),
                aliases=aliases,
                uzbek=uzbek_typography(uzbek),
                note=uzbek_typography(note),
                status=kind,
                general=is_general(general),
            ),
        )
    ids = [e.id for e in entries]
    if len(ids) != len(set(ids)):
        problems.append("Takrorlangan № raqamlar bor")
    return Result(entries, total, counts, skipped, problems)


def shared_forms(entries: list[Entry]) -> list[str]:
    """English forms that point at two different entries (the app keeps the first)."""
    seen: dict[str, int] = {}
    clashes = []
    for entry in entries:
        for form in [entry.english, *entry.aliases]:
            key = form.lower().replace("-", " ")
            if key in seen and seen[key] != entry.id:
                clashes.append(f"'{form}': #{seen[key]} va #{entry.id}")
            seen.setdefault(key, entry.id)
    return clashes


def to_tsv(entries: list[Entry], source_name: str) -> str:
    lines = [
        f"# UZI glossary v{FORMAT_VERSION}. Generated from {source_name} by tools/glossary/convert_uzi_glossary.py.",
        "# Do not edit by hand: fix the workbook and run the converter again.",
        "\t".join(HEADER),
    ]
    for e in entries:
        lines.append(
            "\t".join(
                [
                    str(e.id),
                    e.category,
                    e.english,
                    "; ".join(e.aliases),
                    e.uzbek,
                    e.note,
                    e.status,
                    "1" if e.general else "0",
                ],
            ),
        )
    return "\n".join(lines) + "\n"


def read_workbook(path: Path) -> list[tuple]:
    import openpyxl  # imported here so the tests of the pure functions need no openpyxl

    sheet = openpyxl.load_workbook(path, read_only=True, data_only=True)[SHEET]
    return list(sheet.iter_rows(min_row=2, max_col=10, values_only=True))


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    source, target = Path(argv[1]), Path(argv[2])
    result = convert_rows(read_workbook(source))
    print(f"Jami qatorlar: {result.total_rows}")
    for status, count in sorted(result.status_counts.items()):
        print(f"  {status}: {count}")
    print(f"Lug'atga kiritildi: {len(result.entries)}")
    print(f"  umumiy so'z (J=Ha): {sum(e.general for e in result.entries)}")
    for line in result.skipped:
        print(f"Kiritilmadi: {line}")
    for line in shared_forms(result.entries):
        print(f"Ogohlantirish, bir shakl ikki atamada: {line}")
    if result.problems:
        for line in result.problems:
            print(f"XATO: {line}", file=sys.stderr)
        return 1
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(to_tsv(result.entries, source.name), encoding="utf-8")
    print(f"Yozildi: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
