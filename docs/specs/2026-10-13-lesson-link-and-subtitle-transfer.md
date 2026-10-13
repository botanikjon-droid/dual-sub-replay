# Lesson link and subtitle transfer

## Status

Implemented — requested by the owner ("video dars linki va subtitle ko‘chirish imkoniyatini qo‘sh").
Physical-device and live-YouTube checks are still open.

## Context / problem

The Uzbek build is used to study ultrasound lectures. Two steps were missing from the app:

1. Opening a specific lesson required sharing the link from the YouTube app or browsing to it.
2. Machine translation of medical lectures needs a reviewed, professional translation. There was no
   way to take the transcript out of the app, translate it with a glossary, and bring the reviewed
   translation back.

## Goals

- A drawer item **Dars: havola va subtitr** opens a lesson tools screen.
- Paste or type a YouTube lesson link and open it in the existing single WebView.
- Copy the open video's transcript to the clipboard, or save it as an `.srt` file.
- Import a translated `.srt`/`.vtt` file (or SRT text from the clipboard) for the open video. It
  replaces machine translation for that video, is kept on the device, and is shown again when the
  lesson is reopened. It can be removed to return to machine translation.

## Non-goals

- No video downloading or second player (architecture invariant).
- No lesson library/playlist yet.
- The imported translation is not used in live-caption fallback mode (when the transcript cannot be
  downloaded).

## User-visible behavior

- Invalid link: "Bu YouTube video havolasi emas." under the field; the screen stays open.
- Copy/save/import without an open video: buttons are disabled with "Avval dars videosini oching."
- Transcripts longer than 400,000 characters are not put on the clipboard; the user is told to save a file.
- Import of a file without timecodes: "Faylda subtitr topilmadi. SRT yoki VTT fayl tanlang."
- Files over 8 MiB are rejected.
- After import, captions reload; each imported cue appears once, on the row it overlaps most, and the
  Uzbek dubbing voice reads that text. Rows with no cue show an empty translation line.

## Technical constraints / invariants

- Caption download stays behind `CaptionProvider` (`RecentCaptionTracks` makes the export fetch cheap).
- Exported cues are the `SubtitleMerger` sentences, so a line-by-line translation keeps the same timing.
- Imported files are stored as normalized SRT in `filesDir/imported-translations/<videoId>.srt`.
- `InputStream.readNBytes` is avoided (API 33); minSdk is 26.

## Proposed approach / plan

1. `data/SubtitleFiles.kt`: `formatSrt`, `parseSubtitleFile` (SRT + WebVTT), `parseTimestamp`,
   `applyImportedTranslation`, `toSegments`.
2. `AppViewModel`: `openLessonLink`, `subtitleExport`, `importTranslation`,
   `clearImportedTranslation`; load the stored file in `loadVideo`; skip machine translation and
   overlay imported cues in `publishSubtitleWindow`.
3. `ui/LessonToolsScreen.kt` and a drawer item in `AppNavigation`, wired in `DualSubApp`.

## Acceptance criteria

- [x] SRT export round-trips through the parser with identical timing and text (unit test).
- [x] SRT with BOM, CRLF, tags and multi-line cues, and WebVTT with header/NOTE/short times/cue settings parse (unit tests).
- [x] Malformed, reversed and empty cues are skipped; output is sorted (unit test).
- [x] An imported cue lands on exactly one row (max overlap); rows without a cue get "" (unit tests).
- [ ] CI: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` pass.
- [ ] Device: paste link → video opens; Nusxalash → clipboard has SRT; import edited SRT → Uzbek lines and voice follow it; O‘chirish → machine translation returns.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `SubtitleFilesTest` | Ran locally with kotlinc 2.3.21 + JUnit shim: 10/10 pass. CI must confirm. |
| Formatting | ktlint 1.7.1 report + `tools/check_format_ratchet.py` | Passed locally. |
| Android lint/build | CI `verify-build` | Not available locally (no Android SDK). |
| Physical-device/manual | Scenario in acceptance criteria | Owner, Redmi 13. |
| Live YouTube | Export on a real lecture | Owner. |

## Risks / edge cases

- A translation whose timecodes were changed heavily may map cues to neighbouring rows.
- In "short phrases" format a long sentence shows its Uzbek text on one row and blanks on the others.
