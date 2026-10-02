# Uzbek online translation ("uz" distribution)

## Problem
ML Kit (full) and Bergamot (fdroid) cannot translate Uzbek, so Uzbek viewers cannot use
dual subtitles.

## Decision
Add a third `-Pdistribution=uz` build that keeps the public `OnDeviceTranslator` API
(`prepare`, `translateSingle`, `withSession`, `translateAll`) but translates online:

1. Google Cloud Translation v2 when a key is supplied at build time
   (`-PgoogleTranslateApiKey` or `GOOGLE_TRANSLATE_API_KEY`); never committed.
2. Otherwise the keyless, undocumented gtx endpoint as a fallback.
3. Results are cached in memory and in `TranslationDiskCache`, in a separate
   `subtitle-translations-uz-online-v1` folder.

AppViewModel, the YouTube caption provider, the player, and the UI are unchanged.
The "uz" build does not include ML Kit. Uzbek appears in the language list only in
the "uz" build (`OnlineOnlyLanguages.kt`), so full/fdroid and the website list are unchanged.

## Files
- `app/build.gradle.kts`: allow `uz`, ML Kit only for `full`, two BuildConfig fields.
- `app/src/uz/.../OnDeviceTranslator.kt`, `OnlineTranslationProviders.kt`: new.
- `app/src/main/.../OnlineTranslationFormat.kt`, `OnlineOnlyLanguages.kt`: pure helpers.
- `TranslationLanguages.kt`: one line.
- `app/src/test/.../OnlineTranslationFormatTest.kt`: new.
- `.github/workflows/android.yml`: build and upload `DualSub-Replay-uz.apk`.

## Acceptance criteria
- [ ] `uz` builds in CI; `full` and `fdroid` still build.
- [ ] English captions of a real YouTube video show Uzbek translations on a phone.
- [ ] Repeated lines come from the cache.
- [ ] Network failure shows the existing error state, no crash.
- [ ] gtx fallback works, or its failure is shown clearly.

## Validation so far
- Passed offline: Kotlin 2.3.21 compile of all translation sources with coroutine stubs,
  13 JUnit tests, provider-chain harness, ktlint 1.7.1 (0 new violations), detekt 1.23.8
  (0 smells, src/uz included), `tools/tests` (29 tests).
- Unverified: Gradle/Android build, lint, device tests, live network, real phone.
