# UZI lesson catalog ("Ultratovush mundarijasi", "uz" build)

## Status

Implemented, requested by the owner (2026-10-16): "Dual replay ultratovush tekshiruvi bo‘yicha mundarija yarat …
bo‘limlarga ajrat. Har bir bo‘limni mavzularga bo‘lib video manzillarni joylashtir va joylashtirgan manzillaring
to‘g‘riligini tekshirib chiq, xatoliklar bo‘lsa tuzat."

## Problem

The doctor finds English ultrasound lectures one by one and pastes links. He wants a built-in table of contents by
examination type, each split into topics, with checked YouTube links he can open in the app.

## Goals

- Eleven sections in the owner's order: qorin bo‘shlig‘i a’zolari, ginekologiya, urologiya, neyrosonografiya,
  yurak exokardiografiyasi, qalqonsimon bez, ko‘krak bezi, homila skriningi, qon tomir doppleri, transkranial
  doppler, mushak-bo‘g‘im-nerv tizimi; each split into topics with one or more lectures.
- Every link checked: the video exists and is public (English captions, which the app needs, where a check can see them).
- One tap opens the lecture in the existing single YouTube page; translated lectures are marked.

## Non-goals

- No downloading, no second player/WebView, no YouTube API key. Links only (public videos, YouTube's own page).
- No medical review of lecture content: the catalog lists public lectures; the doctor judges them.

## Design

- `app/src/main/assets/uzi_catalog.tsv`: `section, topic, video_id, title` (title = the verified YouTube title).
  Order of rows = order on screen.
- `data/UziCatalog.kt`: `parseUziCatalog(text)` → sections → topics → videos; malformed rows and bad video IDs
  are skipped; duplicates are dropped.
- `ui/LessonCatalogCard.kt`: card "Ultratovush mundarijasi" in the lesson screen, above "Tarjima qilingan darslar":
  sections expand to topics and lectures; "Ochish" goes through `openTranslatedLesson` → `openLessonLink`
  (`mobileWatchUrl`), i.e. the normal main-frame path; "✓ tarjima qilingan" when the lesson library has it.
- **"✏️ O‘zgartirish"** (owner, 2026-10-16: "yangi ma’lumot qo‘shishim yoki keraksizlarini olib tashlashim
  mumkin"): add a lecture (section, topic — existing names offered as chips, YouTube link, optional title; the
  lesson list's title is used when known), delete a lecture (an emptied topic/section goes too) or a whole section
  (confirmation), move sections and lectures ↑↓, "Asl holiga qaytarish" (confirmation). Pure functions in
  `data/UziCatalog.kt`; `data/UziCatalogStore.kt` keeps the doctor's copy in `filesDir/uzi-catalog.tsv` (asset
  format) once he changes anything, otherwise the asset.
- `tools/catalog/check_catalog_links.py`: checks each video with YouTube's public oEmbed endpoint and the watch
  page (playability, length, caption tracks). Run on a GitHub runner (this workspace cannot reach YouTube).

## Acceptance criteria

- [x] The asset has the eleven sections in order, every row a valid 11-character ID, no duplicates (unit test).
- [x] Parser skips comments, header, malformed rows and bad IDs, keeps order (unit tests).
- [x] Edits: add (new/existing section and topic, no repeats), delete (empty topic/section dropped), move,
      TSV round trip (unit tests).
- [x] Link check: every catalog video exists and is public and embeddable (oEmbed 200), titles match YouTube.
- [ ] English captions of each lecture: not checkable from a runner (YouTube asks datacenter computers to sign
      in); the app reports a lecture without captions when it is opened.
- [ ] Phone: the card lists sections, a lecture opens in the app and its subtitles load.
- [ ] Phone: add, delete, move and reset survive closing the app.

## Validation

| Category | Scenario | Result |
| --- | --- | --- |
| Unit tests | `UziCatalogTest` (8) | passed locally (JUnit4); CI see PR |
| Link check | `python3 tools/catalog/check_catalog_links.py` on ubuntu-latest | see "Link check" |
| Phone | open a lecture from each of two sections | not verified (owner) |

## Link check

Run on ubuntu-latest (GitHub Actions, throwaway branch, 2026-10-16), three passes:

1. First list of 82 lectures: 74 public; 8 failed — 2 deleted (oEmbed 404), 1 removed by YouTube for a policy
   violation, 1 age-restricted, 3 private or embedding blocked (401/403), 1 "not available" in the runner's region.
2. 21 replacement candidates: all public. Replaced: spleen, pelvic anatomy, preterm brain (PVL/IVH), thyroid
   technique (+ a full thyroid course), mitral regurgitation, 20-week anomaly scan, fetal brain; the region-blocked
   infant-hip lecture was dropped (Graf's own lecture stays).
3. Final catalog (82 lectures, 11 sections): 82/82 oEmbed 200, every listed title equal to YouTube's title.

The watch page and Innertube player (visionOS, Android VR clients) answered "Sign in to confirm you're not a bot"
on the runner, so playability in Uzbekistan and caption tracks were not verified.

### Link check, version 2 (ubuntu-latest, throwaway branch, 2026-10-17)

1. 132 lectures: 129 public. Not available on the runner: "Ultrasound Transducers" (Ultrasound Board Review) and Core
   Ultrasound's "Ultrasound Artifacts" parts 1 and 2 (oEmbed 401). Three titles had YouTube's own double spaces or a
   prefix ("Hot Tips - …"); the catalog now uses YouTube's exact titles.
2. 10 replacement candidates: all public. Used: clean and dirty shadowing (Radiology educational videos), reverberation
   (#IMPOCUS), two artifact overviews (POCUS Geek; Dr Sanjeev Mani), transducers segment #6 (jchanmeister).
3. Final catalog: 135 lectures in 12 sections, every ID unique. Captions and playability in Uzbekistan were not
   checkable (YouTube asked the runner to sign in).

## Version 3 (2026-10-17): the whole SHOKALON catalog in the app

Owner (2026-10-17): continue the project without waiting for confirmation; the lessons belong in the app,
ordered by section. The app catalog now holds every working lecture of `docs/shokalon/02_VIDEO_CATALOG/SHOKALON_katalog_v12.xlsx`
(481 lectures; the 3 marked "Ishlamaydi" are left out): 478 rows, 343 of them new (`added` = 3).

- Rows of versions 1–2 keep their section, topic and order.
- A new lecture whose curriculum code already has an app topic goes into that topic. Otherwise it goes to the
  section of its chapter, under a topic named after the curriculum sub-topic.
- Six new sections:
  - "Tekshiruv metodikasi va xulosa yozish" (chapters 7–9)
  - "Pediatrik ultratovush" (chapter 27, except neurosonography and the infant hip)
  - "Yuzaki tuzilmalar" (chapter 24)
  - "Shoshilinch UZI va POCUS" (chapter 29)
  - "Elastografiya, kontrastli UZI va intervension UZI" (chapters 30–32)
  - "Sifat nazorati va kasbiy rivojlanish" (chapter 33)
- Chapter 14 (kidneys) goes to "Urologiya" and chapters 18–21 go to "Homila skriningi", as the app already did.
- A doctor's edited copy receives the 343 new lectures through `mergeCatalogAdditions`. New sections take their
  built-in position; his own order and deletions stay.
- Every link was checked on a runner (oEmbed 200) when it entered the SHOKALON catalog. English captions and
  playability in Uzbekistan were not verified.
- Generator: the xlsx → TSV step is a one-off script, not kept in the repository. The catalog is the source.
