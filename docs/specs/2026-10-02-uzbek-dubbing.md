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

## Settings (B+)
- `DubbingSettings` (own preferences file "dubbing"): on/off, voice (Sardor / Madina),
  video volume while speaking (0-100 %, default 20 %). Loaded only by the uz voice factory,
  so `DubbingSettingsCard` (one line in SubtitleSettingsDialog) shows only in the uz build.
- Switching off stops the clip at once and stops preparing speech; changing the voice drops
  clips of the old voice and prepares the next lines again.
- The uz debug build is named "DualSub UZ" (`src/uzDebug/res`, swapped in by build.gradle.kts).

## Stability and data (C)
Field report: speech went missing in fast talks and after ~30 minutes; data use felt high.
- Playback stops translating for good after one translator error (`translatePlaybackWindow`
  sets `failedAttempt` until the user retries). The uz translator now leaves a sentence blank
  on a failure and reports only 3 failures in a row; its chain retries 3 times (0.5 s, 1.5 s).
- Speech is prepared by 2 workers instead of 1; a failed line is retried after 2, 4, 8 s
  (max 15 s), up to 4 attempts, instead of being dropped after 2 quick failures.
- Look-ahead is 20 s instead of 45 s, so skipping ahead wastes less data.
- The speech cache is trimmed from 50 MB to 40 MB, oldest first.
- The dub itself is ~6 KB per second of speech (48 kbit/s mp3, the only format the service
  gives); the YouTube video is by far the larger share of data.

## Video quality cap
- `DubbingSettings.videoQuality` (Auto, 480p, 360p default, 240p, 144p). Every 3 s the page
  gets `videoQualityScript`, which picks the best level not above the cap (or the lowest
  available) via `movie_player.setPlaybackQualityRange`, and does nothing once it is set.
  YouTube is ~95 % of the data, so this is the largest saving. No-op in full/fdroid.

## Keeping up with fast speech (hold)
Field report: in fast talks and slow networks the voice went missing. Measured with a
virtual-time simulation of the controller (40 sentences, 2 workers, Uzbek speech `r` times
longer than the sentence it translates):

| case | old (cut / skip) | hold (new) |
|---|---|---|
| r=1.6, 3 s sentences | 33 cut, 95 % of the speech heard | 0 cut, 100 %, video waits 7 s in total |
| r=2.0, 5 s sentences | 39 cut, 76 % heard | 0 cut, 100 %, video waits 66 s in total |
| speech takes 8 s to prepare | 0 of 40 spoken | 39 of 40, 98 % |

Design: `DubScheduler.decide` returns Idle / HoldForClip / HoldForAudio / Skipped / Start.
The controller turns holds into the `dubbingHold` flow (HELD, RESUME, DROP); the page pauses
or plays the video with `dubbingHoldScript`, and only plays a video this script paused.
- A hold lasts until the clip ends, so the video does not stutter; a clip ending within 1.2 s
  of the next line is not worth a pause.
- Waiting for speech or a translation lasts at most 6 s per sentence; after 2 give-ups in a
  row, or if the viewer presses play during a hold (10 s), the old rule applies (cut/skip).
  A hold never lasts over 20 s, and a clip that outlives its length by 1.5 s is stopped.
- A line said more than 3 s ago is skipped silently, never spoken late.
- The dub is silent unless the target language is the voice's (Uzbek). Before, an Uzbek voice
  would have read, say, Vietnamese text.
- Settings: hold on/off (default on), fastest speech 1.3x / 1.5x / 1.8x, and a line of what
  happened in the current video.

## Pronunciation of oʻ and gʻ
Field report: the voice read "o'" and "g'" as plain "o" and "g". The voice only pronounces oʻ
and gʻ with the official U+02BB (modifier letter turned comma); Google's translation gives a
plain apostrophe. `uzbekSpeechText` rewrites an apostrophe look-alike after o/O/g/G (' ‘ ’ ʼ ʹ ′
` ´ ＇) to U+02BB and turns curly apostrophes inside a word (ma'no) into a plain one; the glottal
stop and quotation marks are left alone. Only the text sent to the voice changes, never the
subtitles, and the clip cache key uses the rewritten text so earlier clips are not reused.
Sources: Microsoft's own uz-UZ sample sentences use U+02BB, and another project built on the
same voice documents that a plain apostrophe mangles it. Not verified by ear here.

## Validation so far
- Passed offline (dubbing hold: 88 controller checks incl. 21 hold scenarios, 18 JUnit tests, JS hold script 5/5): 9 JUnit tests (`DubbingPlanTest`), 14 controller checks with a fake voice and
  player, 17 protocol checks against values produced by Python edge-tts (token, SSML, frames,
  403 clock-skew retry, errors, cache), ktlint (0 new), detekt (0), tools/tests, full/fdroid
  compile of the dubbing code against android.jar.
- Unverified: Gradle build, live Microsoft service, real phone audio and video ducking.

## Follow-up (2026-10-10)
The speech text is now built by `prepareUzbekSpeech` with a traced rule list, optional unit/abbreviation rules and a voice test in settings. See [UZI glossary and traceable Uzbek speech](2026-10-10-uzi-glossary-and-speech.md).
