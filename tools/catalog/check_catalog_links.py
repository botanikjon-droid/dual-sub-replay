#!/usr/bin/env python3
"""Checks every video of app/src/main/assets/uzi_catalog.tsv on YouTube.

For each row it asks YouTube's public oEmbed endpoint whether the video exists (title and
channel), and reads the public watch page for playability, length and caption tracks. The app
needs an English caption track (manual or automatic) to translate a lesson.

Usage: python3 tools/catalog/check_catalog_links.py [catalog.tsv] [--json out.json]
Exit status 1 when any video is missing or unplayable. Needs network access to youtube.com.
"""

from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[2] / "app/src/main/assets/uzi_catalog.tsv"
VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
    "Accept-Language": "en-US,en;q=0.9",
    "Cookie": "CONSENT=YES+cb; SOCS=CAI",
}


def read_catalog(path: Path) -> list[dict[str, str]]:
    rows = []
    header = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cells = line.split("\t")
        if header is None:
            header = cells
            continue
        rows.append(dict(zip(header, cells)))
    return rows


def get(url: str) -> tuple[int, str]:
    request = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def player_response(html: str) -> dict:
    match = re.search(r"ytInitialPlayerResponse\s*=\s*(\{.+?\})\s*;\s*(?:var|</script>)", html, re.S)
    if not match:
        return {}
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return {}


def check(video_id: str) -> dict:
    result: dict = {"video_id": video_id}
    if not VIDEO_ID.match(video_id):
        result["verdict"] = "BAD_ID"
        return result
    watch = f"https://www.youtube.com/watch?v={video_id}"
    status, body = get("https://www.youtube.com/oembed?format=json&url=" + urllib.parse.quote(watch, safe=""))
    result["oembed_status"] = status
    if status == 200:
        data = json.loads(body)
        result["title"] = data.get("title", "")
        result["channel"] = data.get("author_name", "")
    status, html = get(watch + "&hl=en&gl=US")
    player = player_response(html) if status == 200 else {}
    playability = player.get("playabilityStatus", {})
    details = player.get("videoDetails", {})
    result["playability"] = playability.get("status", "UNKNOWN" if not player else "")
    result["reason"] = playability.get("reason", "")
    result["length_s"] = int(details.get("lengthSeconds", 0) or 0)
    result.setdefault("title", details.get("title", ""))
    result.setdefault("channel", details.get("author", ""))
    tracks = player.get("captions", {}).get("playerCaptionsTracklistRenderer", {}).get("captionTracks", [])
    english = [t for t in tracks if str(t.get("languageCode", "")).startswith("en")]
    result["captions"] = ",".join(
        sorted({("en-auto" if t.get("kind") == "asr" else "en") for t in english})
    ) or ("other" if tracks else "none")
    exists = result["oembed_status"] in (200, 401, 403) or result["playability"] == "OK"
    if not exists:
        result["verdict"] = "MISSING"
    elif result["playability"] not in ("OK", "UNKNOWN", ""):
        result["verdict"] = "UNPLAYABLE"
    elif result["captions"] in ("none", "other"):
        result["verdict"] = "NO_ENGLISH_CAPTIONS"
    else:
        result["verdict"] = "OK"
    return result


def main(argv: list[str]) -> int:
    args = [a for a in argv if not a.startswith("--")]
    out = None
    if "--json" in argv:
        out = Path(argv[argv.index("--json") + 1])
        args = [a for a in args if a != str(out)]
    catalog = Path(args[0]) if args else CATALOG
    rows = read_catalog(catalog)
    results = []
    for row in rows:
        result = check(row["video_id"])
        result.update(section=row["section"], topic=row["topic"], listed_title=row["title"])
        results.append(result)
        print(
            f"{result['verdict']:<20} {row['video_id']}  {result.get('length_s', 0) // 60:>3} min"
            f"  cc={result.get('captions', '')}  {result.get('channel', '')} | {result.get('title', '')}"
            f"  [{row['section']} / {row['topic']}]",
            flush=True,
        )
        time.sleep(0.5)
    if out:
        out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    bad = [r for r in results if r["verdict"] in ("MISSING", "UNPLAYABLE", "BAD_ID")]
    print(f"\n{len(results)} videos, {len(bad)} missing or unplayable, "
          f"{sum(r['verdict'] == 'NO_ENGLISH_CAPTIONS' for r in results)} without English captions")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
