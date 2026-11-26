# SHOKALON subtitle harvest ("SHOKALON: subtitrlarni yig‘ish", "uz" build)

## Status

Implemented (2026-10-17). Owner: continue the SHOKALON project stages without waiting for confirmation
(2026-10-17, "Har safar seni yangilashimni kutma … o‘zing yangilab davom et"). Phone validation is pending.

## Context / problem

SHOKALON's project prompt (stages 4–6) needs every catalog lecture's English subtitle as an untouched `.srt`, named
`SHK_{topic code}_{Video ID}_ORIGINAL.srt`, in a `03_SUBTITLES_ORIGINAL/` folder and one ZIP whose file list can be
compared with the catalog. GitHub runners cannot read YouTube captions ("Sign in to confirm you're not a bot"), so the
catalog check could not tell which of the 478 lectures have captions. The doctor's phone gets captions the way the app
already does when he opens a lesson.

## Goals

- One card in the lesson screen downloads the English subtitle of every catalog lecture, in catalog order.
- Each file keeps YouTube's cues unchanged and is named by the SHOKALON rule.
- The run survives closing the app: progress is saved after every lecture, and the next run continues.
- "ZIP saqlash" writes `03_SUBTITLES_ORIGINAL/*.srt`, `manifest.tsv` (every catalog lecture, its file or why there is
  none) and `README.txt`.

## Non-goals

- No translation (stage 7 starts only after the whole set is collected, per the project prompt).
- No video or audio download, no second WebView, no YouTube API key. Captions only, through `YouTubeCaptionProvider`.
- No sharing of the subtitles beyond the doctor (personal study and translation).

## User-visible behavior

Lesson screen → "SHOKALON: subtitrlarni yig‘ish":

- Counts: `Darslar: done/total · subtitr bor · yo‘q · xato`, a progress bar and the lecture being fetched.
- "Yig‘ishni boshlash" / "Davom ettirish", "To‘xtatish", "ZIP saqlash", and "Subtitrsizlarni qayta tekshirish" when
  some lectures had no captions. The screen stays on while it runs.

## Design

- `app/src/main/assets/shokalon_ids.tsv`: `video_id, code, vid, language` for all 481 catalog videos, generated from
  `docs/shokalon/02_VIDEO_CATALOG/SHOKALON_katalog_v12.xlsx`.
- `data/SubtitleHarvest.kt` (pure, unit-tested):
  - id parsing;
  - catalog order with each lecture once;
  - variant `ORIGINAL` / `ASR-EN` / `ORIGINAL-XX`;
  - file names;
  - records (resume), counts;
  - manifest and ZIP writer.
  A lecture the doctor added himself gets `SHK_000000_YT-{videoId}`.
- `data/SubtitleHarvester.kt`: application-scope coroutine.
  - Fetches each lecture with `YouTubeCaptionProvider.fetch(videoId, ["en"])` and writes the file.
  - Saves records to `filesDir/shokalon-subtitles/records.tsv` after every lecture.
  - Pauses 1.5 s between lectures.
  - Classifies errors: "no public caption track" → `NO_CAPTIONS`; everything else → `FAILED`, which is retried on the
    next run.
- `ui/SubtitleHarvestCard.kt`, placed after the catalog card in `DualSubApp.kt`.

## Acceptance criteria

- [x] Every lecture of the shipped app catalog has a SHOKALON code and a unique Video ID (unit test).
- [x] File names, variants, record round trip, retry rules, counts, ZIP content and manifest rows (unit tests).
- [ ] CI: build, lint, detekt and unit tests pass.
- [ ] Phone: a run collects subtitles, survives closing the app, and "ZIP saqlash" writes a ZIP that opens on a
      computer with the files and `manifest.tsv`.

## Validation

| Check | Result |
| --- | --- |
| `SubtitleHarvestTest` (6) and `UziCatalogTest` (10) | passed locally (kotlinc 2.0.21 + JUnit 4.13.2) |
| ktlint 1.7.1, format ratchet, detekt 1.23.8 on the new files | passed |
| Android build (Compose card, Context code) | CI |
| Phone run over the 478 lectures | not verified (owner) |
