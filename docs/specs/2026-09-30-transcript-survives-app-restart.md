# Returning to a video after Android closed the app skips the caption download

## Status

Implemented. The owner reported on 2026-09-30 that leaving with Home or the app switcher and
coming straight back loads the transcript again, and asked for a fix, together with the other
fixes of that day, in one PR. Local checks pass; see the PR for the final-head CI and the phone
check.

## Context / problem

While Android keeps the app in memory, returning shows the same transcript: nothing on the
stop/start path reloads captions (`setAppVisible` only pauses translation scheduling, and
`openVideo` ignores the video that is already open). When Android closes the app's process in the
background, returning starts a new `AppViewModel`: it deletes the previous process's transcript
files (`subtitle-transcripts`), the WebView reopens the last page, and `loadVideo` downloads the
video's captions from YouTube again ("Finding the best caption track…"). Translations were
already kept on disk (`TranslationDiskCache`), so the download is the slow, network-bound part.

The owner saw this in test builds of the day's fixes but not in his installed release. None of
those fixes changes the stop/start path, so the likely difference is how soon Android closed each
build; a release build can be closed the same way on a busy phone.

## Goals

- Reopening a recently watched video, including after Android closed the app, builds its
  transcript from a copy on the phone instead of downloading the captions again.
- A failed or missing download is never remembered, so Retry and later visits still download.

## Non-goals

- Keeping the WebView page or the video position across a closed process.
- Keeping transcripts forever or across caption-language choices.

## User-visible behavior

- **Before:** returning after Android closed the app showed "Finding the best caption track…"
  and waited for YouTube before the transcript appeared.
- **After:** within a day of watching a video (up to the six most recent ones), its transcript
  comes back from the phone without that download; translations come from the existing
  translation cache.

## Technical constraints / invariants

- The YouTube extraction stays behind `CaptionProvider`; the cache is a decorator
  (`data/RecentCaptionTracks.kt`) around `YouTubeCaptionProvider`, keyed by video ID and the
  preferred caption languages.
- Bounded: at most 6 tracks and 16 MB in `cacheDir/recent-caption-tracks`, entries older than
  24 hours are ignored and deleted, and the least recently used track goes first. Android can
  clear this folder at any time, and `cacheDir` is not backed up.
- Disk or format errors never fail a fetch; a damaged entry is deleted and the captions download.
- Plain JUnit4 tests with `org.json` from the unit-test classpath (Android provides it at run
  time).

## Proposed approach / plan

1. `RecentCaptionTracks(delegate, directory)`: read a matching, fresh entry; otherwise call the
   delegate and store its successful, non-empty result (temporary file, then rename), then trim.
2. `AppViewModel`'s production constructor wraps `YouTubeCaptionProvider` with it.
3. PRIVACY.md and the technical context mention the new cache.

## Acceptance criteria

- [x] A new cache instance on the same folder (a restarted process) returns the stored track,
  words and caption-language list included, without calling the network provider.
- [x] Another video, another caption-language choice, an expired entry, a failed download, a
  damaged entry and an oversized track all go to the network provider.
- [x] Only the most recently used tracks are kept (`RecentCaptionTracksTest`).
- [ ] Owner's phone: watch a video, leave with Home or the app switcher, come back; if Android
  closed the app, the transcript appears without "Finding the best caption track…" waiting on
  the network.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest`, including `RecentCaptionTracksTest` | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| Managed-device/emulator | Existing suite; its tests pass fake providers to `AppViewModel`, so the cache is not in their path | CI |
| Physical-device/manual | The scenario in the last acceptance criterion; `adb shell am kill` on the backgrounded app reproduces a closed process | Owner's phone |

## Risks / edge cases

- Captions that YouTube adds or corrects within a day of a visit appear on the next visit after
  the entry expires, or after switching the caption language.
- The cache holds caption text of recently watched videos in the app's private cache folder.

## Release intent

`release:patch` (bug fix), in the combined PR with the day's other fixes. The version is an
estimate until the release automation reserves it.

## Implementation result

As planned.

## Validation result

- `RecentCaptionTracksTest` (7 tests) passes locally.
- Full local checks, final-head CI and the owner's phone check: see the PR.
