"""Small SubRip (.srt) reader and SHOKALON file-name rules shared by the stage 6 and stage 8 checkers.

File names follow the SHOKALON rule from the project prompt:
SHK_{topic code without dots}_{Video ID}_{variant}.srt, for example SHK_031106_V004_ORIGINAL.srt and
SHK_031106_V004_UZ-LATIN.srt. A lecture the doctor added himself is named SHK_000000_YT-{videoId}_...
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

FILE_NAME = re.compile(
    r"^SHK_(?P<code>\d{6})_(?P<vid>V\d{3,}|YT-[A-Za-z0-9_-]{11})_(?P<variant>[A-Z]{2,}(?:-[A-Z0-9]+)*)\.srt$"
)
SOURCE_VARIANT = re.compile(r"^(ORIGINAL|ASR)(-[A-Z]{2,3})?$")
TRANSLATED_VARIANT = re.compile(r"^UZ-LATIN(-V\d+)?$")
TIME = re.compile(r"^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})$")
TIMING = re.compile(r"^\s*(\S+)\s*-->\s*(\S+)")


@dataclass(frozen=True)
class SubtitleName:
    code: str  # six digits, e.g. 031106
    vid: str  # V004 or YT-xxxxxxxxxxx
    variant: str  # ORIGINAL, ASR-EN, UZ-LATIN, UZ-LATIN-V2, ...

    @property
    def dotted_code(self) -> str:
        return f"{self.code[0:2]}.{self.code[2:4]}.{self.code[4:6]}"


def parse_name(name: str) -> SubtitleName | None:
    match = FILE_NAME.match(Path(name).name)
    if not match:
        return None
    return SubtitleName(match["code"], match["vid"], match["variant"])


@dataclass(frozen=True)
class Cue:
    index: str
    start: str
    end: str
    text: str
    start_ms: int
    end_ms: int


class SrtError(ValueError):
    pass


def time_ms(value: str) -> int:
    match = TIME.match(value.strip())
    if not match:
        raise SrtError(f"noto‘g‘ri vaqt: {value!r}")
    hours, minutes, seconds, millis = (int(part) for part in match.groups())
    if minutes > 59 or seconds > 59:
        raise SrtError(f"noto‘g‘ri vaqt: {value!r}")
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis


def parse_srt(text: str) -> list[Cue]:
    """Cues in file order. Raises SrtError when a block has no valid timing line."""
    text = text.lstrip("﻿").replace("\r\n", "\n").replace("\r", "\n")
    cues: list[Cue] = []
    for block_number, block in enumerate(re.split(r"\n\s*\n", text.strip()), 1):
        lines = [line for line in block.split("\n")]
        if not block.strip():
            continue
        timing_at = next((i for i, line in enumerate(lines[:2]) if "-->" in line), None)
        if timing_at is None:
            raise SrtError(f"{block_number}-blokda vaqt qatori yo‘q")
        match = TIMING.match(lines[timing_at])
        if not match:
            raise SrtError(f"{block_number}-blokda vaqt qatori buzilgan")
        start, end = match.group(1), match.group(2)
        index = lines[0].strip() if timing_at == 1 else ""
        cue_text = "\n".join(lines[timing_at + 1 :]).strip()
        cues.append(Cue(index, start, end, cue_text, time_ms(start), time_ms(end)))
    return cues


def read_text(data: bytes) -> str:
    """UTF-8 (with or without BOM); anything else is reported by the caller as unreadable."""
    return data.decode("utf-8-sig")
