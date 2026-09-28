# F-Droid build variant and listing

## Status

Implemented, awaiting owner review. The owner asked whether the app can be published on F-Droid
and to prepare it, then chose Mozilla Bergamot as the F-Droid build's translation engine and
approved adding its source as git submodules. That covers this preparation. It does not authorize
submitting to F-Droid, merging, or publishing.

## Context / problem

F-Droid only builds apps whose whole dependency tree is free software. DualSub Replay passes
most checks:

| Check | Result |
| --- | --- |
| License | MIT ([LICENSE](../../LICENSE)), accepted by F-Droid |
| Repositories | Google Maven, Maven Central and the Gradle Plugin Portal only |
| Analytics, ads, crash reporting, Firebase | None |
| Tagged source | Every release has a `vX.Y.Z` tag whose `app/build.gradle.kts` holds that release's `appVersionCode` and `appVersionName` |
| Prebuilt binaries in the tree | Only `gradle/wrapper/gradle-wrapper.jar`, which F-Droid replaces itself |
| Dependencies | **`com.google.mlkit:translate` is proprietary.** It also pulls Play Services Tasks/Basement and Google's data-transport telemetry. |

ML Kit is the only blocker, and it is the translation engine, the core of the app.

F-Droid will also label the app with the **NonFreeNet** anti-feature, because it browses YouTube
and downloads YouTube captions. That is a label on the listing, not a rejection (NewPipe carries it).

## Goals

- A build switch that produces an APK with no ML Kit or other proprietary code, without changing
  the default build, CI task names, APK paths, or the release workflow.
- CI proves on every PR that the F-Droid variant still builds and contains no proprietary Google classes.
- Store listing metadata in the fastlane layout F-Droid reads.
- The F-Droid build translates on-device with Mozilla Bergamot, built from source.
- A draft fdroiddata recipe that builds the engine.

## Non-goals

- Matching ML Kit's language list: the F-Droid build supports the languages Mozilla has models for.
- 32-bit ARM (`armeabi-v7a`) and 32-bit x86 native builds.
- Submitting a merge request to F-Droid's fdroiddata repository.
- Reproducible builds / developer-signed F-Droid APKs. F-Droid will sign with its own key, so
  users cannot switch between the GitHub and F-Droid builds without reinstalling.

## User-visible behavior

GitHub releases and previews: unchanged. They keep ML Kit.

F-Droid variant (`-Pdistribution=fdroid`): browsing, captions, replay and saved words work as in
the full build. The first translation for a language pair downloads Mozilla's models (about
20 to 40 MB per direction) from Firefox Remote Settings, checks their SHA-256 and keeps them in
app storage; later translations are offline. Pairs without English pivot through English (two
models). Pairs Mozilla has no model for show "X to Y is not available for offline translation.";
32-bit ARM phones show "Offline translation is not supported on this device's processor." Subtitle
text never leaves the device.

## Technical constraints / invariants

- [Translation stays on-device with no user-provided key](../project/tech-stack.md#architecture-invariants).
  A replacement engine should keep that; an online service would change the privacy promise.
- Release automation reads `app/build/outputs/apk/{debug,release}/app-*.apk` and runs
  un-flavored task names, so this change must not add Android product flavors.
- Do not change Gradle version constants.

## Proposed approach / plan

1. Add a `distribution` Gradle property (`full` default, `fdroid`). Each value adds
   `app/src/<distribution>/java` to the main source set. ML Kit is only a dependency of `full`.
   The `fdroid` build also turns off AGP's dependency-info signing block, which F-Droid rejects.
2. Move `translation/OnDeviceTranslator.kt` to `app/src/full/java`. Add an `app/src/fdroid/java`
   class with the same API and no engine.
3. Add both folders to detekt input and move the file's ktlint budget entry.
4. Add a `fdroid-build` CI job: `assembleRelease -Pdistribution=fdroid`, then fail if the dex
   contains `com/google/mlkit`, `com/google/android/gms` or `com/google/firebase` classes.
5. Add `fastlane/metadata/android/en-US` (title, descriptions, icon, screenshots) and a draft
   recipe in [docs/fdroid](../fdroid/README.md).
6. Bergamot engine for the F-Droid build:
   - Submodules `app/src/fdroid/cpp/translations` (mozilla/translations, pinned commit; its
     `inference/` folder is Bergamot + Marian) and `app/src/fdroid/cpp/pcre2` (tag pcre2-10.44).
   - `app/src/fdroid/cpp/CMakeLists.txt` builds them with the NDK for `arm64-v8a` and `x86_64`,
     plus a JNI bridge (`bergamot_jni.cpp`: load, translate, pivot, release).
   - `BergamotCatalog.kt` (in `main`, unit-tested): parse Mozilla's model catalog, pick the newest
     complete model per pair, map app language codes, route through English, build the model config.
   - `BergamotModelStore.kt`: cached catalog, verified downloads, old versions removed.
   - The F-Droid `OnDeviceTranslator` keeps the full build's API and caches, and serialises the
     engine with a mutex. `AppViewModel` passes it a model directory (ignored by the ML Kit build).
7. CI: `fdroid-build` builds the native libraries and checks both are in the APK;
   `fdroid-device-tests` runs `BergamotEngineDeviceTest` with real en↔vi models on the API 36 emulator.

Why a property rather than product flavors: flavors rename every task and APK path used by
`android.yml`, `release-on-main.yml` and the managed-device job. The property keeps those untouched.
The F-Droid recipe passes it with `gradleprops` and removes the ML Kit line with `prebuild`,
because F-Droid's scanner reads every dependency line in the build file.

## Engine decision (made 2026-09-28: Bergamot)

The owner picked Bergamot from these options:

| Option | Fit |
| --- | --- |
| Mozilla Bergamot (Firefox Translations models), on-device | Keeps the on-device promise. Native C++ built from source with the NDK; fewer languages than ML Kit; largest effort. Already used by F-Droid translator apps. |
| YouTube's own auto-translated caption track | Small change inside the caption provider; no new service. Relies on another undocumented YouTube behavior, cannot translate single tapped words, and moves translation off-device. |
| LibreTranslate server chosen by the user | Small client. Sends subtitle text to a third-party server and needs a server URL, which breaks the current privacy and "no setup" promises. |

## Acceptance criteria

- [x] `assembleDebug` and `assembleRelease` without the property still include ML Kit and behave as before.
- [x] `assembleRelease -Pdistribution=fdroid` succeeds, contains `libdualsub_bergamot.so` for
  `arm64-v8a` and `x86_64`, and its dex has no ML Kit, Play Services or Firebase classes.
- [x] Bergamot translates English→Vietnamese and pivots Vietnamese→English→Vietnamese on an
  Android emulator (`fdroid-device-tests`).
- [x] Existing CI (`verify-build`, `managed-device-tests`) passes unchanged, plus the new F-Droid jobs.
- [x] The fastlane short description is at most 80 characters and the icon is 512×512 PNG.
- [x] The draft recipe names the anti-feature, the build switch, the submodules, the NDK and the update check.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including `BergamotCatalogTest` | Local and CI |
| Android lint/build | `lintDebug assembleDebug`; `lintRelease assembleRelease -Pdistribution=fdroid` then the library and dex checks | Local and CI |
| Managed-device/emulator | Existing `pixel2Api36DebugAndroidTest` passes (full variant); `BergamotEngineDeviceTest` passes (F-Droid variant) | CI |
| Physical-device/manual | Install the F-Droid APK on an arm64 phone, open a captioned video, pick a language: models download once, then translations appear | Owner device; not run |
| Live YouTube | Same as above against a real video | Owner device; not run |
| Documentation/process | fdroiddata recipe reviewed against F-Droid's build metadata reference | Draft only; `fdroid lint` not run |

## Risks / edge cases

- `fdroid checkupdates` must read the version from `appVersionCode`/`appVersionName`, because
  `versionCode` in the build file is an expression. The recipe uses `UpdateCheckData` for that.
- F-Droid's build server must know the Gradle 9.5 wrapper checksum and support AGP 9.3; if not,
  the first F-Droid build waits for their tooling update.
- Bergamot needs NDK r28: NDK r29's Clang rejects SentencePiece code in the pinned sources.
- pathie-cpp (inside Marian) references `glob`/`iconv`, which Android declares only from API 28.
  They are declared weak and never called, so the library still loads on API 26 and 27.
- Mozilla's catalog and CDN URLs are not a stable public API. If they change, downloads fail with
  an error and original captions keep working; the URLs are constants in `BergamotCatalog.kt`.
- The native build adds several minutes per ABI to CI and F-Droid builds.
- Changelogs in fastlane are keyed by `versionCode`, which release automation assigns at merge
  time, so none are added here. F-Droid links the GitHub releases page instead.

## Release intent

`release:patch` (default). The default APK does not change; the release carries the switch so
F-Droid can build from its tag. Estimated next version v1.1.2 (latest tag and release v1.1.1,
no draft reservations seen); the number is reserved at merge time.

## Implementation result

Steps 1 to 7 implemented. Deviations:

- AGP 9's built-in Kotlin ignores `java.srcDir`, so the variant folder is added with `kotlin.srcDir`.
- NDK pinned to r28c (`28.2.13676358`) instead of the newest r29 (see risks).
- Only `arm64-v8a` and `x86_64` are built; 32-bit devices get a clear error instead of translation.
- Marian only enables its Ruy float GEMM on ARM, so an x86-64 build without a system BLAS
  aborted on the first translation. The first `fdroid-device-tests` run caught this on the
  x86_64 emulator; x86_64 now uses Ruy as well.
- Marian's `git_revision.h` rule depends on a `.git` path that does not exist inside a
  submodule, so the CMake file writes that header itself.

## Validation result

Local run on Linux, JDK 21, Android SDK 36 (2026-09-28):

- Passed: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug`.
- Passed: `assembleRelease -PtestReleaseSigning=true` (full). APK 29.8 MB; its dex references
  `com/google/mlkit`, `com/google/android/gms` and `com/google/firebase`, so the CI check does catch them.
- Passed: `assembleRelease -Pdistribution=fdroid`. Unsigned APK 2.1 MB; no ML Kit, Play Services
  or Firebase classes.
- Passed: `python3 -m unittest discover -s tools/tests -p 'test_*.py'` (29 tests).
- Passed: Bergamot on the Linux host with the same sources and Mozilla's en→vi / vi→en models:
  translation (about 0.25 s per sentence) and the vi→en→vi pivot.
- Passed: `lintRelease assembleRelease assembleDebugAndroidTest -Pdistribution=fdroid`. Unsigned
  APK 9.4 MB with both native libraries (arm64 library 9.9 MB, needs only `liblog`, `libandroid`,
  `libdl`, `libm`, `libc`, with `glob`/`iconv` weak); no ML Kit, Play Services or Firebase classes.
- Passed: `tools/fetch_bergamot_test_models.py` downloads and verifies the test models.
- Not run locally (no emulator acceleration here): managed-device tests, including
  `BergamotEngineDeviceTest` (CI runs it). Not run: install on a physical phone, live YouTube,
  `fdroid lint`/`fdroid build`.

CI on 9449d33: `verify-build`, `managed-device-tests`, `fdroid-build` and `fdroid-device-tests`
passed. `BergamotEngineDeviceTest` ran both tests on the API 36 x86_64 emulator with real models
(2 run, 0 skipped, 0 failed): English→Vietnamese in 0.75 s and the Vietnamese→English→Vietnamese
pivot in 0.28 s. Later commits need fresh checks; see the PR.
