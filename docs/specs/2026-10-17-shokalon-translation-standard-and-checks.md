# SHOKALON stages 6–8: subtitle ZIP check, translation standard, translation check

## Status

Implemented (2026-10-17) as preparation; no subtitle is translated yet. Owner: continue the SHOKALON stages without
waiting for confirmation ("Har safar seni yangilashimni kutma … o‘zing yangilab davom et"). The project prompt forbids
translating before every subtitle is collected; these tools only make stages 6–8 ready.

## Problem

Stage 5 runs on the doctor's phone (`docs/specs/2026-10-17-shokalon-subtitle-harvest.md`). When its ZIP arrives, the
prompt needs: the file list compared with the catalog (stage 6); one medical translation standard for Claude and
ChatGPT (stage 7); and a check of every translation (stage 8).

## Design

- `tools/shokalon/srt.py`: SubRip reader and the SHOKALON name rule (`SHK_{code}_{vid}_{variant}.srt`).
- `tools/shokalon/check_subtitle_zip.py`: reads the harvest ZIP (`03_SUBTITLES_ORIGINAL/`, `manifest.tsv`) and
  `app/src/main/assets/shokalon_ids.tsv`.
  - Errors: a bad name, an unknown Video ID, a code that differs from the catalog, two files for one lecture, an
    unreadable SRT, a missing manifest, unexpected files.
  - Writes `report.md` and `lectures.tsv`, one row per catalog lecture with its state.
- `docs/shokalon/TARJIMA_STANDARTI.md` and `docs/shokalon/prompts/tarjima_prompti.txt`: the standard. It merges the
  prompt's section 8, the doctor's `medical-uzbek-translator` skill and the app glossary (glossary first).
  - Names: `UZ-LATIN`, `-V2` for revisions.
  - Spelling: o‘/g‘ = U+2018, tutuq = U+2019.
  - Lines: 42 characters, at most 2 per cue.
  - Open decisions for the doctor are listed.
- `tools/shokalon/check_translation.py`: implements section 7 of the standard.
  - Errors: cue count, numbers or timings changed; empty cues; Cyrillic; a missing number.
  - Warnings: a dropped unit, English left in, a glossary term missing, o‘/g‘ marks, line length.
  - Works on one pair or on two folders; with folders it takes the newest `UZ-LATIN-Vn` of each lecture.

## Acceptance criteria

- [x] `tools/tests/test_shokalon_subtitles.py` (13): names, SRT parsing, clean and broken ZIPs, the shipped ID table,
      translation errors and warnings, decimal comma, folder pairing, glossary loading.
- [x] All `tools/tests` pass (50).
- [ ] First real harvest ZIP from the phone passes through `check_subtitle_zip.py`.

## Also in this change

`NavigationRecoveryUiTest` sends Back through the activity's `OnBackPressedDispatcher` instead of
`Espresso.pressBack()`. The drawer's `BackHandler` is still what closes the menu, but the test no longer waits for
emulator window focus, which made managed-device-tests fail on CI (RootViewWithoutFocusException).
