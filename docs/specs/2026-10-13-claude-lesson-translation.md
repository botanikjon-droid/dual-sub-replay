# Claude lesson translation and the growing glossary ("uz" build)

## Status

Implemented, requested by the owner: "Dasturga shu video yoki keyingi video SRT faylini Claude yuklab avtomatik
tarjima qilib qayta yuklab olish tugmasi qo‘shish" and "lug‘at video ko‘rish davomida rivojlanib borishiga ham
e’tibor ber". Live API, phone and CI checks are still open.

## Problem

The share-to-Claude flow (2026-10-13 lesson transfer spec) works but is manual: share, wait in the Claude app,
share back. Machine translation (Google, Gemini) breaks Uzbek sentence structure across rows. Terms outside the
227-entry UZI glossary are translated differently from lesson to lesson.

## Goals

- **Shu darsni Claude bilan tarjima qilish** (lesson tools screen): the open lesson's transcript (the same
  SubtitleMerger sentences that are exported as `.srt`) is translated by Claude and imported as the lesson's
  translation through the existing import path (`imported-translations/<videoId>.srt`). Display, dubbing and
  reopening work as for a hand-imported file.
- **Avtomatik** switch: every newly opened lesson without a stored translation is translated in the background,
  once per app session. This covers "the next video": each lesson is translated as soon as it is opened.
- The doctor's own API key, a model choice (Sonnet default, Haiku, Opus), kept only in the private `dubbing`
  preferences, never in the code, repository or CI.
- The prompt is the doctor's medical-uzbek-translator skill: rebuild sentences across items, fix ASR errors only
  with clear context and report doubtful ones, Uzbek word order, no dangling words at item edges, no added
  content, glossary terms with Uzbek suffixes, o‘/g‘ with U+2018 and the glottal stop with U+2019.
- **O‘sib boruvchi lug‘at** (growing glossary): Claude lists terms it translated that no glossary covers. They
  are stored as *pending* in `filesDir/learned-glossary.tsv`. The doctor approves, edits, deletes or adds
  terms on the lesson screen. Approved terms are mandatory in every later request; pending ones are sent as
  suggestions so wording stays consistent. Terms already in the UZI glossary are not suggested again.
- Doubtful places (likely misheard words) are listed under the Claude card with the options Claude saw.

## Non-goals

- Pre-translating a video that is not opened yet: the app has no playlist or library to know the next lesson.
- A server or shared key (needed before the app is given to other doctors).
- Feeding learned terms to the Google/Gemini path.

## Design

| Part | File |
| --- | --- |
| Request/reply, chunking, tool schema, apostrophes, Uzbek error texts (pure) | `translation/ClaudeTranslation.kt` |
| HTTPS transport, retries, chunk loop that splits a failed chunk in half | `translation/ClaudeClient.kt` |
| Learned glossary model, TSV, merge, review order (pure) and its file store | `data/LearnedGlossary.kt`, `data/LearnedGlossaryStore.kt` |
| One run at a time, auto mode, progress, doubts, learning | `ui/ClaudeLessonCoordinator.kt` |
| Cards on the lesson screen | `ui/ClaudeLessonCards.kt`, `ui/LessonToolsScreen.kt` |
| Settings | `dubbing/DubbingSettings.kt` |
| Wiring | `ui/AppViewModel.kt` (`lessonSegments`, `translateLessonWithClaude`, `onLessonLoaded`), `ui/DualSubApp.kt` |

- Messages API, `anthropic-version: 2023-06-01`, forced tool call `submit_translation` so the reply is structured.
- 40 sentences per request, 3 earlier sentences (with their finished Uzbek) and 2 later ones as context. A reply
  cut at `max_tokens` or missing items is retried as two halves, down to 5 sentences.
- HTTP 429/5xx/529 are retried after 3, 10 and 30 s (or `retry-after`); 401/403/404 stop with an Uzbek reason.
- Machine translation keeps playing while Claude works; the translation is imported once, at the end, because
  an imported part blanks the rows it does not cover.
- Only public caption text is sent.

## Acceptance criteria

- [x] Chunks cover every sentence once, in order; a failed chunk is split and retried (unit tests).
- [x] The prompt carries context, finished Uzbek, approved and suggested terms; the tool is forced (unit tests).
- [x] Replies missing items, with foreign items, cut at max_tokens or unreadable are rejected (unit tests).
- [x] A busy service is retried; a wrong key stops at once with an Uzbek message (unit tests).
- [x] Learned glossary: TSV round trip, merge keeps the doctor's wording, known terms skipped, review order (unit tests).
- [ ] CI: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` and the uz APK build pass.
- [ ] Phone: key entered → button → progress → Uzbek lines and voice follow Claude's text; reopening keeps it.
- [ ] Phone: Avtomatik on → opening a new lesson translates it in the background.
- [ ] Phone: suggested terms appear as pending; approve/edit/delete/add persist after restart and reach the next run.

## Validation

| Check | Result |
| --- | --- |
| `ClaudeTranslationTest` (9), `LearnedGlossaryTest` (4) | PASS locally: kotlinc 2.0.21 + JUnit 4.13.2 with a local org.json stand-in. CI must confirm with the real one. |
| ktlint 1.7.1 on new files (0 violations) and the ratchet on edited files | PASS locally |
| detekt 1.23.8 complexity (main, full, fdroid, uz) | PASS locally, 0 smells |
| Android compile, lint, Compose UI, device tests | NOT TESTED locally (no Android SDK in the sandbox) |
| Live Claude API call, translation quality, cost per lesson | NOT TESTED; owner on the phone |

## Risks

- Cost: every run is billed to the doctor's API account; auto mode is off by default.
- A model ID the account cannot use returns 404; the doctor picks another model.
- The API key is kept in the app's private preferences like the Gemini key; Android backups include it.

## Field feedback 1 (2026-10-14): Gemini whole lessons

The doctor: the Gemini free tier allows about 20 requests a day per model, and the sentence batches used
them up on about 20 sentences. Request: Gemini should translate the whole subtitle, so 20 requests give at
least 20 lessons, with an excellent translation prompt.

- **Whole-lesson Gemini** (`translation/GeminiLessonTranslation.kt`): the lesson's whole transcript in ONE
  `generateContent` request (up to 400 sentences, `maxOutputTokens` 65,536, temperature 0.2), JSON answer in
  the same schema as Claude (translations, new terms, doubtful places), converted to Gemini's schema dialect.
  The answer is imported as the lesson's translation, so a reopened lesson costs no request. Only a reply
  cut at `MAX_TOKENS` is split in two. A model out of quota (429) or unknown (404) rests and the next model,
  with its own quota, gets the request; when all rest, the lesson screen names the time.
- **Setting** "Har bir darsni bitta so‘rovda tarjima qilish" (`geminiWholeLesson`, on by default). With
  Gemini chosen as translator, every newly opened lesson without a stored translation is translated
  automatically. The sentence-batch Gemini path during playback is off in this mode; Google Translate covers
  playback until the lesson's Gemini translation is imported. Off restores the old behaviour.
- **One prompt for both engines** (`lessonSystemPrompt`): the medical-uzbek-translator skill, extended with
  a bad/good word-order example, more known ASR errors, the Uzbek medical register (established terms,
  informal lecturer remarks, course adverts) and "no Cyrillic".
- The lesson engine is now generic (`LessonTranslator` + `ClaudeBackend` / `GeminiBackend`).
- Tests: `GeminiLessonTranslationTest` (5): schema dialect and prompt, reply with thoughts and fences, cut
  reply, one request per lesson with model hand-over on 429, all models used up, wrong key stops at once.
  18 lesson tests pass locally; ktlint ratchet and detekt pass.
- Unverified: live Gemini reply for a long lecture, real free-tier limits per model, translation quality.

## Field feedback 2 (2026-10-14): update from the menu

The doctor asked for an update button so he does not have to ask for the APK each time.
- Menu item **Dasturni yangilash** (Uzbek build only, `ui/AppUpdate.kt`, pure parts in `AppUpdateRelease.kt`).
  It reads the public uz-latest release from the GitHub REST API, compares its CI run ("(run N)" in the notes)
  with the installed version name ("-uz-buildN"), and opens the APK link in the browser, which downloads it
  and hands it to Android's installer. The app needs no install permission and sends nothing.
- `AppUpdateTest` (3) passes locally. Unverified: the download and install on the phone.
