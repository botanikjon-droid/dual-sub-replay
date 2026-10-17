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

## Version 2 (2026-10-17): SHOKALON basics section, more lectures, updates reach the doctor's copy

Owner (2026-10-17): "Sen yaratgan birlamchi mundarija papkalar endi ularni rivojlantirish kerak dastur logotipni
ham o‘zgartiramiz" — the catalog grows from the SHOKALON video catalog (`SHOKALON_katalog_v1.xlsx`, section I of the
33-chapter curriculum and the earlier abdominal list), and the app gets the SHOKALON name and the owner's portrait icon.

- New first section **"Ultratovush asoslari: fizika va apparat"**: 24 topics, 37 lectures (Sononerds' 25-part physics
  course, Marc Kohli, Core Ultrasound, AIUM bioeffects, USF cleaning, …). The owner can move it with ↑↓.
- 12 more lectures in "Qorin bo‘shlig‘i a’zolari" (full abdominal protocol, liver anatomy and segments, portal vein
  Doppler, elastography, CBD, pancreas, normal-vs-abnormal review) and "Urologiya" (renal protocol, renal pathology).
- The asset gets a fifth column `added` (catalog version; rows without it are version 1). Before this, once the doctor
  edited the catalog his copy hid every later built-in change. Now `UziCatalogStore` remembers the version his copy
  contains (`filesDir/uzi-catalog-version.txt`, 1 when missing) and `mergeCatalogAdditions` adds only lectures of newer
  versions that his copy lacks: his order, his own lectures and his deletions of older lectures stay; a new section is
  placed where it is in the built-in catalog.
- Launcher (uz build): name "SHOKALON" (`src/uzDebug/res/values/strings.xml`), adaptive icon from the owner's portrait
  in a cyan ring on the app's dark teal (`src/uzDebug/res/mipmap-*/ic_shokalon_*.png`). The APK file name and the
  `uz-latest` tag stay, because in-app updates look for them; the release title becomes "SHOKALON (latest)".

| Check | Result |
| --- | --- |
| `UziCatalogTest` (10, incl. version column and merge) | passed locally (kotlinc 2.0.21 + JUnit 4.13.2) |
| ktlint, detekt on changed files | passed |
| Link check of the v2 catalog on ubuntu-latest | see below |
| Phone: icon, name, new section, merge into an edited copy | not verified (owner) |
