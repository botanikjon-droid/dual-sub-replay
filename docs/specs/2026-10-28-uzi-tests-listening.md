# UZI testlari: listen, test yourself, search (SHOKALON)

## Status

Implemented — requested by the owner (Dr. Otajonov) on 2026-10-28: "connect the UZI test trainer to
SHOKALON so the Sardor voice reads it". Physical-device listening not yet verified.

## Context / problem

The doctor prepares for the ultrasound qualification exam from a bank of 465 Cyrillic questions with
keyed answers. A web page with the browser's voice was made first; Russian voices read Uzbek poorly.
SHOKALON already has the Uzbek neural voices (Sardor/Madina) in the `uz` build.

## Goals

- The whole bank inside SHOKALON, opened from the sections screen ("UZI testlari" card).
- **Eshitish**: the Uzbek voice reads question → pause to think → answer (1–3 times) → plain
  explanation → a warning where the key looks doubtful; or one of ten topic lessons.
- **Test**: 20/50/100/all questions, shuffled options, multi-answer questions, result and
  "retry the wrong ones".
- **Qidirish**: search by words in Latin or Cyrillic, or by question number.

## Non-goals

- No editing of the bank in the app; no background (screen-off) playback service.
- General (`full`/`fdroid`) builds: the card lives on the SHOKALON sections screen only.

## User-visible behavior

Sections screen → "UZI testlari" card → full-screen dialog with three tabs and topic chips.
While listening the screen stays on. Speech comes from the existing Edge voice and is cached on disk,
so a second listen works offline. A failed request shows "Ovoz olinmadi…" and stops.

## Technical constraints / invariants

- No new WebView, player architecture or network host: speech goes through the existing
  `DubbingVoiceSample.synthesize` hook (Edge voice, `uz` build only) and `MediaPlayer`.
- Bank text stays Cyrillic for display; the voice gets an Uzbek Latin copy (`uziTestSpeechText`).

## Proposed approach / plan

- `assets/uzi_tests.json`: questions (`n,t,q,o,a,e,f,s`) and lessons.
- `data/UziTestText.kt`: Cyrillic→Latin, spoken text (units, ranges, decimals), search keys.
- `data/UziTests.kt`: model, parser, filter/search, `UziTestStore`.
- `dubbing/UziTestReader.kt`: read scripts and the reading loop with next-line prefetch.
- `ui/UziTestsScreen.kt`: card, host and the three tabs; wired into `ShokalonHomeScreen` and `DualSubApp`.

## Acceptance criteria

- [x] The shipped bank parses to 465 questions, each with a key, an answer phrase and a known topic; 10 lessons.
- [x] Cyrillic is transliterated by the official rules (е/ц by position, ў/ғ with ʻ).
- [x] "portal vena" and "портал вена" find the same questions; "#153" finds question 153.
- [x] The read script orders question, pause, repeated answer, explanation, warning.
- [ ] On the doctor's phone the Sardor voice reads a question and its answer (manual).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `UziTestsTest`, `UziTestReaderTest` pass | CI `testDebugUnitTest` (uz and full) |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug`; new files ktlint- and detekt-clean | CI |
| Physical-device/manual | Open card, play 3 questions, run a 20-question test, search "jigar" | Redmi 13, uz-latest APK |
| Live YouTube | Not applicable | — |

## Risks / edge cases

- The Edge voice endpoint is undocumented; when it fails, the error line shows and nothing else breaks.
- Some keyed answers are doubtful; they are kept as keyed and marked with a warning line.

## Results

- New Kotlin files: ktlint 1.7.1 and detekt 1.23.8 report no issues (run locally).
- Transliteration and spoken-text functions checked locally with kotlinc 2.1.21.
- Android build and unit tests: pending CI.
