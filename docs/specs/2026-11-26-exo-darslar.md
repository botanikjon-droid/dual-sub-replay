# ExoKG darslari: echocardiography lessons with drawn echo simulation (SHOKALON)

## Status

Implemented — requested by the owner on 2026-11-26: "open a section in SHOKALON that teaches me
echocardiography from the books; Sardor can read the material and ask questions; it must have
pictures/simulation". Physical-device check not yet done.

## Context / problem

The doctor is learning (paediatric) echocardiography. Text alone does not work for views, defects and
shunts: he needs to see where a defect sits in a view and which way the shunt flows.

## Goals

- "ExoKG darslari" card on the SHOKALON sections screen, under the test banks.
- 38 lessons in three modules: 12 standard views, 24 defects (ASD types, VSD types, PDA, AVSD,
  Fallot, TGA, ccTGA, truncus, tricuspid atresia, Ebstein, single ventricle, CoA, HLHS, TAPVR,
  AS, PS, ALCAPA, Kawasaki), Doppler basics and pressure/shunt calculations.
- Each lesson: a drawn echo picture (sector scan with chambers; for a defect the hole and moving
  shunt jets coloured by colour-Doppler rules: red towards the apex probe, blue away), paragraphs
  (clinical picture, echo signs, measurements, pitfall, fact), and 2–4 questions with explanations.
- Sardor reads the lesson ("Darsni o'qish") or runs question → options → pause → answer → why
  ("Savol-javob"). Best score per lesson is kept on the phone.

## Non-goals

- No third-party images (lecture slides, book figures) in this public repository: pictures are
  drawn from shape data in `assets/exo_darslar.json`.
- No WebView (single-WebView invariant), no new network host, no new library.

## Technical approach

- `data/ExoLessons.kt`: model, `parseExoCourse` (org.json), `ExoLessonStore` (asset + SharedPreferences).
- `ui/ExoScan.kt`: Compose `Canvas` renderer of the 400×330 picture space (ellipses, circles,
  SVG path strings via `PathParser`, tubes, valve lines, labels), defect changes and animated jets.
- `ui/ExoLessonsScreen.kt`: card, host, course list, lesson view; voice via existing
  `uziTestVoice()` / `UziTestReader` (uz build only; other builds show a hint).
- Content: written in Uzbek from Аверкин's lecture, Воробьёв "Амбулаторная эхокардиография у детей"
  (2010, paraphrased) and ASE 2024 paediatric TTE guideline.

## Acceptance criteria

- [x] Card opens the course; lessons list shows modules, numbers and best scores.
- [x] Every lesson with a view draws its picture; defects show the hole and jets.
- [x] Questions mark right/wrong and show the explanation; score saved when all are answered.
- [x] Unit tests: course parses, ids unique, 4 distinct options, valid answer, known views/defects,
      jet colour rule, spoken question script.
- [ ] Physical device: pictures, voice reading and scrolling checked by the owner.

## Validation

`ExoLessonsTest` (JUnit); ktlint and detekt clean on the new files; Android CI on the branch.
