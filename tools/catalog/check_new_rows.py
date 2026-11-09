#!/usr/bin/env python3
"""Checks only the newest catalog version's videos on YouTube and reports them as GitHub annotations.

CI runs it after the catalog grows, because the computer that adds the rows cannot reach YouTube. One
notice lists every video with its verdict, length and captions, so the result can be read from the
check run's annotations. Never fails the build: a bad video is removed from the catalog by hand.

Usage: python3 tools/catalog/check_new_rows.py [catalog.tsv]
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_catalog_links import CATALOG, check, read_catalog  # noqa: E402

CHUNK = 20


def annotation(lines: list[str], part: int, parts: int) -> str:
    # Newlines in an annotation message are written as %0A.
    return f"::notice title=Catalog links {part}/{parts}::" + "%0A".join(lines)


def main(argv: list[str]) -> int:
    rows = read_catalog(Path(argv[0]) if argv else CATALOG)
    latest = max(int(row.get("added") or 1) for row in rows)
    lines = []
    for row in (r for r in rows if int(r.get("added") or 1) == latest):
        result = check(row["video_id"])
        lines.append(
            f"{result['verdict']} {row['video_id']} {result.get('length_s', 0) // 60}min "
            f"cc={result.get('captions', '')} | {result.get('channel', '')} | {result.get('title', '')}"
        )
        time.sleep(0.5)
    parts = [lines[i:i + CHUNK] for i in range(0, len(lines), CHUNK)] or [["no rows"]]
    for index, part in enumerate(parts, 1):
        print(annotation(part, index, len(parts)))
    print(f"version {latest}: {len(lines)} videos checked")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
