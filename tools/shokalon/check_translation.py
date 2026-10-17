#!/usr/bin/env python3
"""SHOKALON stage 8: checks an Uzbek subtitle translation against its English source.

Rules come from docs/shokalon/TARJIMA_STANDARTI.md, section 7:
- errors: different cue count, cue numbers or timings; empty translated cue; Cyrillic letters; a number of the source
  cue missing from the translation; a file that is not UTF-8 SubRip;
- warnings: a unit (mm, cm, kPa, MHz …) dropped; English words left over; a glossary term of the source whose Uzbek
  form is missing; o‘/g‘ written with another mark or mixed marks; lines longer than 42 characters or more than 2
  lines in a cue.

Usage:
  python3 tools/shokalon/check_translation.py SOURCE.srt TRANSLATION.srt [--report out.md]
  python3 tools/shokalon/check_translation.py --dirs SOURCE_DIR TRANSLATED_DIR [--report out.md]
The second form pairs SHK_{code}_{vid}_ORIGINAL|ASR-*.srt with SHK_{code}_{vid}_UZ-LATIN[-Vn].srt (newest version).
Exit status 1 when any file has errors.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from srt import SOURCE_VARIANT, TRANSLATED_VARIANT, Cue, SrtError, parse_name, parse_srt, read_text  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
GLOSSARY = ROOT / "app" / "src" / "main" / "assets" / "uzi_glossary.tsv"

MAX_LINE = 42
MAX_LINES = 2
NUMBER = re.compile(r"\d+(?:[.,]\d+)?")
UNIT = re.compile(r"(?<![A-Za-z])(mm|cm/s|cm|kPa|MHz|mL|ml|dB|m/s|kHz|Hz)(?![A-Za-z])")
CYRILLIC = re.compile(r"[Ѐ-ӿ]")
# o‘/g‘ written with ASCII ' , ` , ʻ (U+02BB) or ’ (U+2019) instead of ‘ (U+2018).
WRONG_OKINA = re.compile(r"(?i)\b\w*[og]['`ʻ’]\w*")
RIGHT_OKINA = re.compile(r"(?i)[og]‘")
ENGLISH = {
    "the", "and", "with", "this", "that", "these", "those", "you", "your", "we", "our", "is", "are", "was", "were",
    "of", "to", "in", "on", "it", "its", "be", "have", "has", "can", "will", "here", "there", "what", "which", "see",
}


@dataclass
class GlossaryTerm:
    english: list[str]
    uzbek: str

    def in_source(self, text: str) -> bool:
        for term in self.english:
            flags = 0 if term.isupper() else re.IGNORECASE
            if re.search(rf"(?<![A-Za-z]){re.escape(term)}(?:s|es)?(?![A-Za-z])", text, flags):
                return True
        return False

    def in_translation(self, text: str) -> bool:
        lowered = text.lower()
        words = [word for word in re.split(r"[\s/,-]+", self.uzbek.lower()) if len(word) > 2]
        # Uzbek adds suffixes: a word counts as present when its stem (word minus up to 3 letters) is there.
        return all(word[: max(4, len(word) - 3)] in lowered for word in words) if words else True


def read_glossary(path: Path = GLOSSARY) -> list[GlossaryTerm]:
    terms = []
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    for row in csv.DictReader(lines, delimiter="\t"):
        if row.get("general") == "1":
            continue
        english = [row["english"]] + [alias.strip() for alias in (row.get("aliases") or "").split(";") if alias.strip()]
        uzbek = re.sub(r"\s*\(.*?\)", "", row["uzbek"]).strip().strip('"')
        if uzbek:
            terms.append(GlossaryTerm([term for term in english if term], uzbek))
    return terms


@dataclass
class Report:
    name: str
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def numbers(text: str) -> list[str]:
    return [value.replace(",", ".") for value in NUMBER.findall(text)]


def check_cues(source: list[Cue], target: list[Cue], glossary: list[GlossaryTerm], report: Report) -> None:
    if len(source) != len(target):
        report.errors.append(f"bloklar soni: asl {len(source)}, tarjima {len(target)}")
    marks = set()
    for number, (src, uz) in enumerate(zip(source, target), 1):
        where = f"blok {uz.index or number}"
        if (src.index, src.start, src.end) != (uz.index, uz.start, uz.end):
            report.errors.append(f"{where}: raqam yoki vaqt o‘zgargan ({src.index} {src.start} --> {src.end})")
        if not uz.text.strip():
            report.errors.append(f"{where}: tarjima bo‘sh")
            continue
        if CYRILLIC.search(uz.text):
            report.errors.append(f"{where}: kirill harfi bor")
        missing = [value for value in numbers(src.text) if value not in numbers(uz.text)]
        if missing:
            report.errors.append(f"{where}: son tushib qolgan: {', '.join(missing)}")
        units = [unit for unit in UNIT.findall(src.text) if unit.lower() not in uz.text.lower()]
        if units:
            report.warnings.append(f"{where}: birlik tushib qolgan: {', '.join(units)}")
        leftover = sorted({word for word in re.findall(r"[A-Za-z]+", uz.text) if word.lower() in ENGLISH})
        if leftover:
            report.warnings.append(f"{where}: inglizcha so‘z qolgan: {', '.join(leftover)}")
        for term in glossary:
            if term.in_source(src.text) and not term.in_translation(uz.text):
                report.warnings.append(f"{where}: lug‘at atamasi “{term.uzbek}” yo‘q ({term.english[0]})")
        wrong = WRONG_OKINA.findall(uz.text)
        if wrong:
            report.warnings.append(f"{where}: o‘/g‘ belgisi noto‘g‘ri: {', '.join(sorted(set(wrong))[:5])}")
            marks.add("wrong")
        if RIGHT_OKINA.search(uz.text):
            marks.add("right")
        lines = uz.text.split("\n")
        if len(lines) > MAX_LINES:
            report.warnings.append(f"{where}: {len(lines)} qator (ko‘pi bilan {MAX_LINES})")
        long = [line for line in lines if len(line) > MAX_LINE]
        if long:
            report.warnings.append(f"{where}: qator {max(len(line) for line in long)} belgi (ko‘pi bilan {MAX_LINE})")
    if marks == {"wrong", "right"}:
        report.warnings.append("faylda o‘/g‘ ning aralash shakllari bor")


def check_pair(source_path: Path, target_path: Path, glossary: list[GlossaryTerm]) -> Report:
    report = Report(target_path.name)
    source_name, target_name = parse_name(source_path.name), parse_name(target_path.name)
    if target_name is None or not TRANSLATED_VARIANT.match(target_name.variant):
        report.warnings.append("tarjima fayli nomi SHK_{kod}_{Video ID}_UZ-LATIN.srt emas")
    elif source_name is not None and (source_name.code, source_name.vid) != (target_name.code, target_name.vid):
        report.errors.append("tarjimaning kodi yoki Video ID’si asl fayldan farq qiladi")
    try:
        source = parse_srt(read_text(source_path.read_bytes()))
        target = parse_srt(read_text(target_path.read_bytes()))
    except UnicodeDecodeError:
        report.errors.append("fayl UTF-8 emas")
        return report
    except SrtError as error:
        report.errors.append(f"SRT buzilgan: {error}")
        return report
    check_cues(source, target, glossary, report)
    return report


def pairs(source_dir: Path, translated_dir: Path) -> tuple[list[tuple[Path, Path]], list[str]]:
    sources = {}
    for path in sorted(source_dir.glob("*.srt")):
        name = parse_name(path.name)
        if name and SOURCE_VARIANT.match(name.variant):
            sources[(name.code, name.vid)] = path
    newest: dict[tuple[str, str], tuple[int, Path]] = {}
    for path in sorted(translated_dir.glob("*.srt")):
        name = parse_name(path.name)
        if not name or not TRANSLATED_VARIANT.match(name.variant):
            continue
        version = int(name.variant.rsplit("-V", 1)[1]) if "-V" in name.variant else 1
        key = (name.code, name.vid)
        if key not in newest or version > newest[key][0]:
            newest[key] = (version, path)
    matched = [(sources[key], path) for key, (_, path) in sorted(newest.items()) if key in sources]
    orphans = [path.name for key, (_, path) in sorted(newest.items()) if key not in sources]
    return matched, orphans


def render(reports: list[Report], orphans: list[str]) -> str:
    lines = ["# SHOKALON tarjima tekshiruvi", "", "| Fayl | Xato | Ogohlantirish |", "| --- | --- | --- |"]
    lines += [f"| `{r.name}` | {len(r.errors)} | {len(r.warnings)} |" for r in reports]
    for report in reports:
        if report.errors or report.warnings:
            lines += ["", f"## {report.name}", ""]
            lines += [f"- **Xato:** {item}" for item in report.errors]
            lines += [f"- Ogohlantirish: {item}" for item in report.warnings]
    if orphans:
        lines += ["", "## Asl fayli topilmagan tarjimalar", ""] + [f"- `{name}`" for name in orphans]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--dirs", nargs=2, type=Path, metavar=("SOURCE_DIR", "TRANSLATED_DIR"))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--glossary", type=Path, default=GLOSSARY)
    args = parser.parse_args(argv)
    glossary = read_glossary(args.glossary)
    orphans: list[str] = []
    if args.dirs:
        matched, orphans = pairs(*args.dirs)
    elif len(args.paths) == 2:
        matched = [(args.paths[0], args.paths[1])]
    else:
        parser.error("ikki fayl yoki --dirs bering")
    reports = [check_pair(source, target, glossary) for source, target in matched]
    text = render(reports, orphans)
    if args.report:
        args.report.write_text(text, encoding="utf-8")
    print(text)
    return 1 if any(r.errors for r in reports) or orphans else 0


if __name__ == "__main__":
    sys.exit(main())
