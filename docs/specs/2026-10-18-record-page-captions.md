# Record the captions YouTube shows when the track cannot be downloaded

## Problem

For some lectures the app cannot download the caption track (the provider fails), so it falls back to live
captions: it reads the one or two lines the YouTube page shows and translates them, but keeps nothing. The page
removes each line after it is spoken, so the lecture never gets a subtitle file. The doctor asked (2026-10-18) to
keep these captions so such videos get subtitles.

## Decision

- In live fallback the app records every caption word with the media time it appeared at (`CaptionRecording`):
  rolling auto captions are de-duplicated by matching the screen text against the words already on screen;
  a replaced caption (author captions) is recorded whole. A seek starts a new pass; a pass replaces what was
  recorded for the part it plays, so watching a part again fixes it instead of repeating it. A missing sample
  (captions not captured, app in background) ends the pass, so nothing recorded earlier is erased.
- Words become cues of at most 84 characters (2 × 42), split at sentence ends, pauses over 2.5 s and 7 s length;
  each word keeps its own time for highlighting.
- Stored per video in `filesDir/recorded-captions/{videoId}.tsv`, saved 3 s after a change, on video change and
  in `onCleared`. Only rendered caption text is stored; no audio or video, no second WebView.
- "Dars: havola va subtitr" shows a card: progress and watched ranges; **Subtitr sifatida ishlatish** makes the
  recording the lesson's caption track (replay, translation, SRT export, sharing for translation, voice);
  **Yozishni davom ettirish** returns to recording; **O‘chirish** (with confirmation) deletes it.
- The SHOKALON harvest uses a recording the user chose to use when YouTube gives no captions for that lecture:
  `SHK_…_ASR-EN.srt`, manifest note `ekrandan yozilgan`.

## Acceptance criteria

1. Rolling captions produce each word once, in order, with its time; a sample repeated with the same revision is
   ignored. (`RecordedCaptionsTest`)
2. Re-watching a part replaces it; a skipped part stays empty; coverage lists the watched ranges.
3. Cues are at most 84 characters, have a positive duration and meet the next cue when split from one update.
4. A recording round-trips through its file; the store rejects ids that are not YouTube ids; only a recording
   marked complete is used as subtitles.
5. The recorder saves on video change, reloads on reopen, and deletes on request. (`CaptionRecorderTest`)
6. The lesson-tools card and the live status line show the right text. (`RecordedCaptionsHintTest`)

## Limits

- Captions are captured only while they are captured for display (subtitle panel open or overlay mode), as before.
- Times come from the page's caption changes, polled every 0.5 s: about half a second of error.
- Coverage is shown as time ranges; the app does not know the video's length.

## Validation

Unit tests above, ktlint ratchet, detekt, Android CI (4 jobs). Not verified on a physical phone with a real
lecture yet.
