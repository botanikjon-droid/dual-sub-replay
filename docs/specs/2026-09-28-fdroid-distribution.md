# F-Droid build variant and listing

## Status

Draft (implementation of the build switch done; engine decision open). The owner asked whether the app can be published on F-Droid and to prepare it. That
request covers this preparation. It does not authorize submitting to F-Droid, merging, or
publishing. One product decision is open: which free translation engine the F-Droid build uses.

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
- A draft fdroiddata recipe that is ready once the translation engine exists.

## Non-goals

- Choosing or implementing the free translation engine (open decision below).
- Submitting a merge request to F-Droid's fdroiddata repository.
- Reproducible builds / developer-signed F-Droid APKs. F-Droid will sign with its own key, so
  users cannot switch between the GitHub and F-Droid builds without reinstalling.

## User-visible behavior

GitHub releases and previews: unchanged. They keep ML Kit.

F-Droid variant (`-Pdistribution=fdroid`): browsing, captions, replay and saved words work.
Translating between two different languages fails with "Translation is not available in this
F-Droid build yet. Original captions still work." until an engine is chosen. It must not be
submitted in that state.

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

Why a property rather than product flavors: flavors rename every task and APK path used by
`android.yml`, `release-on-main.yml` and the managed-device job. The property keeps those untouched.
The F-Droid recipe passes it with `gradleprops` and removes the ML Kit line with `prebuild`,
because F-Droid's scanner reads every dependency line in the build file.

## Open decision: F-Droid translation engine

| Option | Fit |
| --- | --- |
| Mozilla Bergamot (Firefox Translations models), on-device | Keeps the on-device promise. Native C++ built from source with the NDK; fewer languages than ML Kit; largest effort. Already used by F-Droid translator apps. |
| YouTube's own auto-translated caption track | Small change inside the caption provider; no new service. Relies on another undocumented YouTube behavior, cannot translate single tapped words, and moves translation off-device. |
| LibreTranslate server chosen by the user | Small client. Sends subtitle text to a third-party server and needs a server URL, which breaks the current privacy and "no setup" promises. |

## Acceptance criteria

- [ ] `assembleDebug` and `assembleRelease` without the property still include ML Kit and behave as before.
- [ ] `assembleRelease -Pdistribution=fdroid` succeeds and the APK dex has no ML Kit, Play Services or Firebase classes.
- [ ] Existing CI (`verify-build`, `managed-device-tests`) passes unchanged, plus the new `fdroid-build` job.
- [ ] The fastlane short description is at most 80 characters and the icon is 512×512 PNG.
- [ ] The draft recipe names the anti-feature, the build switch and the update check.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local and CI |
| Android lint/build | `lintDebug assembleDebug`; `assembleRelease -Pdistribution=fdroid` then the dex check | Local and CI |
| Managed-device/emulator | Existing `pixel2Api36DebugAndroidTest` passes (full variant) | CI |
| Physical-device/manual | Install the F-Droid APK, open a captioned video: captions load, translation shows the message | Owner device; not run |
| Live YouTube | Not applicable until an engine is chosen | |
| Documentation/process | fdroiddata recipe reviewed against F-Droid's build metadata reference | Draft only; `fdroid lint` not run |

## Risks / edge cases

- `fdroid checkupdates` must read the version from `appVersionCode`/`appVersionName`, because
  `versionCode` in the build file is an expression. The recipe uses `UpdateCheckData` for that.
- F-Droid's build server must know the Gradle 9.5 wrapper checksum and support AGP 9.3; if not,
  the first F-Droid build waits for their tooling update.
- Changelogs in fastlane are keyed by `versionCode`, which release automation assigns at merge
  time, so none are added here. F-Droid links the GitHub releases page instead.

## Release intent

`release:patch` (default). The default APK does not change; the release carries the switch so
F-Droid can build from its tag. Estimated next version v1.1.2 (latest tag and release v1.1.1,
no draft reservations seen); the number is reserved at merge time.

## Implementation result

Steps 1 to 5 implemented as planned. One deviation: AGP 9's built-in Kotlin ignores
`java.srcDir`, so the variant folder is added with `kotlin.srcDir`.

## Validation result

Local run on Linux, JDK 21, Android SDK 36 (2026-09-28):

- Passed: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug`.
- Passed: `assembleRelease -PtestReleaseSigning=true` (full). APK 29.8 MB; its dex references
  `com/google/mlkit`, `com/google/android/gms` and `com/google/firebase`, so the CI check does catch them.
- Passed: `assembleRelease -Pdistribution=fdroid`. Unsigned APK 2.1 MB; no ML Kit, Play Services
  or Firebase classes.
- Passed: `python3 -m unittest discover -s tools/tests -p 'test_*.py'` (29 tests).
- Not run locally: managed-device tests (CI), device install, `fdroid lint`/`fdroid build`.

Final-head CI: see the PR.
