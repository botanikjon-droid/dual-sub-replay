# Pictured lesson notes ("uz" build)

## Status

Implemented, requested by the owner: "NotebookLM matn qiladi, rasm bo‘lmaydi; ultratovush rasmsiz ahamiyatini
yo‘qotadi". He checked that a phone screenshot of the lesson video in the app shows the ultrasound picture.

## Problem

The konspekt (2026-10-13 spec, field feedback 6) is text only. Similar projects (BiliNote, MIT; lec2note,
Apache-2.0) cut frames from a downloaded video file. The app does not download YouTube videos (architecture
invariant and YouTube's terms).

## Design

- The notes request also asks for 4–12 `key_frames` `{t, caption}`: moments where the lecturer shows an image
  ("as you can see", "measure", …). The transcript is sent with `[m:ss]` line times.
- **"📷 Kadrlarni videodan olish"** (notes card): the lesson screen closes; for each planned frame the page video
  is paused at `t` (origin-checked script), the `<video>` element's box is read from the page, and that part
  of the app's own window is copied with `PixelCopy` — like the phone's screenshot, nothing is downloaded.
  A frame that is almost all black stops the run with a message (the phone protects the video layer).
- **📷 button** above the subtitles: the frame on screen now, captioned with the current subtitle, goes into
  the notes (notes may then hold only the doctor's own pictures).
- Pictures: `filesDir/lesson-notes/<videoId>/<seconds>.jpg` (JPEG 88), private, for personal study.
  Rewriting the notes keeps taken pictures. At most 12 planned frames; taken ones are never dropped.
- Files: `ui/WebVideoFrames.kt`, `data/LessonNotes.kt`, `data/LessonLibraryStore.kt`,
  `translation/LessonNotesRequest.kt`, `ui/LessonNotesCoordinator.kt`, `ui/LessonNotesCards.kt`,
  `ui/TranslatorBadge.kt`, `ui/AppViewModel.kt`, `ui/YouTubeBrowserScreen.kt` (registers the frame source).

## Acceptance criteria

- [x] Frames parse, sort, dedupe (5 s), keep taken pictures at the limit; JSON round trip (unit tests).
- [x] The prompt carries line times; the schema asks for `key_frames` (unit tests).
- [x] Frame scripts re-check the YouTube origin; video box in device pixels; black detection (unit tests, CI).
- [ ] Phone: notes written → "Kadrlarni videodan olish" → pictures appear under "Rasmli konspekt".
- [ ] Phone: 📷 while watching adds the current frame with the subtitle as caption.
- [ ] Phone: PixelCopy is not black on the owner's phone (a system screenshot was not black; PixelCopy of the
      app window may still differ — then the next step is Android's screen-capture permission).
