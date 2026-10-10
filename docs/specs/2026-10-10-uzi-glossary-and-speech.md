# UZI glossary and traceable Uzbek speech ("uz" build)

## Goal
Help an Uzbek ultrasound (UZI) doctor follow English lectures:
1. Show the doctor-approved Uzbek term and note for ultrasound terms in the captions.
2. Flag captions whose machine translation did not use the approved term. Do not silently rewrite them.
3. Keep the subtitle on screen, the text sent to the voice and the pronunciation rules apart, with every rule traceable.
4. Let the doctor compare the available voices by ear with one fixed test sentence.

## Audit findings (2026-10-10, commit 7892cce)
| Finding | Where |
|---|---|
| No glossary existed in the app; the reviewed workbook was not integrated | n/a |
| Translation: Google Cloud if `GOOGLE_TRANSLATE_API_KEY` is set at build time, otherwise keyless gtx; no terminology check | `src/uz/.../OnlineTranslationProviders.kt`, `OnDeviceTranslator.kt` |
| Voices: only Microsoft Edge "Read aloud" `uz-UZ-SardorNeural` and `uz-UZ-MadinaNeural`. **"Neurolink" does not exist in the code or the git history** | `src/uz/.../EdgeSpeech.kt` |
| The free Edge endpoint accepts one `<voice>` and `<prosody>` only, so `<phoneme>` and custom lexicons are unavailable. Fixes must be made in the text | `edgeSsml` |
| The oʻ/gʻ fix (U+02BB) already existed but had no trace of what changed | `dubbing/UzbekSpeechText.kt` |
| Sentence-level translation and the dub "hold" sync were already in place; no defect found | `PlaybackTranslation.kt`, `DubbingPlan.kt` |
| 9 stale copies of source files at `app/*.kt` (left from a web upload, not compiled) | removed |
| No secrets committed; the API key is a CI secret | `android.yml` |

## Design
- **Source → asset:** `docs/glossary/UZI_atamalari_tekshirilgan.xlsx` → `tools/glossary/convert_uzi_glossary.py` → `app/src/main/assets/uzi_glossary.tsv`. The asset is TSV, so the app needs no spreadsheet library. Status rules:
  - "To'g'ri" keeps E/F.
  - "Tuzatildi" uses H/I; an empty H or I keeps E or F.
  - "O'chirish" rows are dropped.
  - "Yangi" rows are added.
  - Any other status is left out and reported.

  Only apostrophe typography is unified (o‘ g‘, glottal ’).
- **`data/UziGlossary.kt` (pure):**
  - Parser, and a matcher that picks the leftmost-longest match and never lets matches overlap ("main portal vein" > "portal vein"; hepatic ≠ portal).
  - Hyphen and space are equivalent. Regular plurals and possessives match. Acronyms (RI, TGC, TI-RADS) match only in capitals.
  - Common words (J = Ha) are excluded from the chips and shown only when tapped.
  - `translationUsesApprovedTerm` checks Uzbek variants. A parenthesis gives an alternative, as do `/` and `;`. Uzbek case and possessive endings are handled with a stem prefix.
  - `glossaryChecksForRow` finds terms in the whole sentence, so a term split across two rows is still found. A row that holds only a slice of the sentence is not judged.
- **UI:**
  - `GlossaryTermRow` shows chips under each caption card (en → uz only), for example "portal vein → darvoza venasi". A ⚠ chip means the approved term was not found in the translation. Tapping a chip shows the Uzbek term, the note, the category and whether a doctor approved or corrected it.
  - The word dialog shows a "UZI lug‘ati" box. Its meaning field is pre-filled with the approved term instead of an online word translation.
  - Subtitle text is never modified.
- **Speech:** `prepareUzbekSpeech(display, options, acronyms)` returns the display text, the TTS text and a list of applied rules (before/after).
  - Confirmed rules: `okina` (o‘/g‘ → U+02BB) and `tutuq` (curly glottal stop → ').
  - Experimental rules, off by default and switched on in settings: `birliklar` ("12 kPa" → "12 kilopaskal", only after a number) and `qisqartmalar` (RI → "rezistivlik indeksi", taken from the glossary; "(RI)" after the full name is dropped).
  - The clip cache key is the final TTS text. `EdgeSpeech` logs the trace (`adb logcat -s EdgeSpeech`).
- **Voice test:** the settings card has one button per voice. Each reads `UZI_VOICE_TEST_TEXT` and shows the TTS text and the rules that ran.

## Acceptance criteria
- All 227 workbook rows are accounted for: 172 approved + 55 corrected = 227 entries, 20 of them common words. CI fails if the asset changes count or status mix (`UziGlossaryTest`, `tools/tests/test_uzi_glossary.py`).
- The display text is identical before and after speech preparation.
- "jigar venasining" and other plain o/g words are unchanged.
- hepatic vein ≠ portal vein. lesion / mass / nodule / tumor map to four different Uzbek terms.
- The 32 sample captions in `app/src/test/resources/uzi_translation_cases.tsv` find their expected terms. Their reference translations use the approved terms. Their speech copies differ from the display text only in apostrophes.

## Validation
| Check | Result |
|---|---|
| New JUnit tests: `UziGlossaryTest` (15), `UziGlossaryRowTest` (2), `UzbekSpeechPipelineTest` (9), existing `UzbekSpeechTextTest` (4) | PASS. Compiled and run locally with kotlinc 2.0.21 + JUnit 4.13.2, outside Gradle |
| Other existing pure unit tests that compile without Android (`TranslationCacheTest`, `TranslationDiskCacheTest`, `PronunciationTest`, `PronunciationCacheTest`, `BackupRulesTest`, `PlaybackSnapshotGateTest`, `YouTubeUrlParserTest`, `SubtitleStoreTest`) | PASS (75 tests in total, together with the above) |
| `tools/tests` (37, including 6 new glossary tests; the workbook-sync test ran because openpyxl was installed) | PASS |
| ktlint 1.7.1 format ratchet, detekt 1.23.8 complexity (main, full, fdroid, uz) | PASS |
| Gradle build, Android lint, Compose UI compile, the remaining unit tests, managed-device tests | NOT TESTED. The sandbox could not reach Maven Central, Google Maven or services.gradle.org (HTTP 403) |
| Live gtx / Google Cloud translation, live Microsoft voice, audio by ear | NOT TESTED. No network access to those services from the sandbox |

## Open items for the doctor
See `docs/glossary/review-2026-10-10.md` for glossary entries that look medically doubtful (not changed).
