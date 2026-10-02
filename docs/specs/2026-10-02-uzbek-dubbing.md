# Uzbek voice dubbing ("uz" build)

## Goal
Speak each translated caption sentence in Uzbek while the video plays, like a dub.
No phone has an Uzbek TTS voice by default (checked on a Redmi 13), so the voice is online.

## Design
- `dubbing/DubbingPlan.kt` (main, pure): `dubLines` joins a sentence's row slices back into
  the whole translation; `DubScheduler` decides what to speak per playback tick; `dubTempo`
  speeds a clip up to 1.5x so it ends before the next sentence.
- `dubbing/DubbingController.kt` (main): prepares clips 45 s ahead, plays them with
  `MediaPlayer` (no audio focus, so the video keeps playing), pauses/resumes/stops with the
  video, and sets `dubbingDucksVideo` while speaking.
- `createDubbingVoice`: `null` in full/fdroid (controller idle), Microsoft Edge "Read aloud"
  voice `uz-UZ-SardorNeural` in uz (`src/uz/.../EdgeSpeech.kt`, follows edge-tts 7.2.8,
  clips cached in `cache/dub-uz-v1`).
- AppViewModel: 6 one-line calls (create, rows, playback tick, reset on clear/hide/close).
- YouTubeBrowserScreen: one `LaunchedEffect` lowers the page video to 20 % while speaking.

## Rules
- A line plays once and in order; a line more than 2 s late is skipped; a clip still speaking
  1.2 s after the next line was due is cut. A seek forgets earlier lines; rewinding repeats them.
- A line failing synthesis twice is skipped.

## Validation so far
- Passed offline: 9 JUnit tests (`DubbingPlanTest`), 14 controller checks with a fake voice and
  player, 17 protocol checks against values produced by Python edge-tts (token, SSML, frames,
  403 clock-skew retry, errors, cache), ktlint (0 new), detekt (0), tools/tests, full/fdroid
  compile of the dubbing code against android.jar.
- Unverified: Gradle build, live Microsoft service, real phone audio and video ducking.
