# Spoken line stays on screen when a pause reveals translations

## Status

Implemented. Unit tests and the build pass locally. The new managed-device test runs in CI. The
owner's phone check is pending. The owner asked for a PR; that does not authorize merging or
publishing.

## Context / problem

With **Translated captions: Only when paused**, pausing adds a translation under every row of the
transcript. On the owner's phone, when the spoken line was near the bottom of the panel, the rows
above it grew and pushed it below the bottom edge. The owner had to scroll down to read the line
they paused on.

`SubtitleTimeline` in `ui/DualSubApp.kt` only moved the list when the active row changed or the
panel got shorter. Rows growing in place moved nothing.

## Goals

- When the layout changes under the active row (rows growing on pause, the panel shrinking), the
  active row and its translation stay fully on screen.
- Move the list only when the row is actually clipped, and only as far as needed, so the rows above
  it stay on screen too.

## Non-goals

- No change to how the list follows playback, seeks, or the panel's first placement.
- No pulling back when the user scrolls the active row away themselves.

## User-visible behavior

- **Before:** pause with the spoken line near the bottom; the translations push it off screen.
- **After:** the spoken line and its translation end in the bottom slot of the panel, with the
  earlier lines above it. If it already fits, nothing moves. A row taller than the panel shows its
  top.

## Technical constraints / invariants

- Keep logic in small `internal` functions tested with plain JUnit4.
- Only one scroll effect should react to layout changes, so two effects do not race for the list.

## Proposed approach / plan

1. `revealScrollDelta(offset, size, viewportStart, viewportEnd)` returns the smallest scroll that
   fits a row, or 0.
2. Replace the viewport-shrink effect in `SubtitleTimeline` with one effect that watches the list
   layout for the active row:
   - It remembers whether the active row fully fit in the last layout that was not mid-scroll.
   - If it fit and now it is clipped, scroll by `revealScrollDelta`.
   - If it fit and was pushed out in one frame, `scrollToItem` it, then move back by the free
     space so it sits in the bottom slot.
   - The earlier rule stays: after the panel shrinks, a row that was partly visible and is now gone
     is scrolled to the top.
   - While the list is scrolling (the user's drag or fling, or playback following), it only
     records state.

## Acceptance criteria

- [x] `revealScrollDelta` returns 0 for a fitting row, the clipped amount at the bottom, the top
  offset at the top, and top-alignment for a row taller than the panel
  (`PlaybackArchitectureTest.activeRowRevealMovesOnlyAsFarAsTheClippedPart`).
- [ ] With translations shown only when paused and the spoken line as the last row that fits,
  pausing leaves that line and its translation inside the panel
  (`SubtitleUiTest.pausingKeepsTheActiveLineAndItsTranslationOnScreen`, managed device in CI).
- [ ] Owner's phone: the report's scenario no longer needs a scroll.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the new test | CI `managed-device-tests` |
| Physical-device/manual | Pause with the spoken line near the bottom; it stays fully visible | Owner's phone |
| Live YouTube | Same as above on a real video | Owner's phone |

## Risks / edge cases

- If the user scrolls the active row off screen and then pauses, nothing moves. That is intended.
- The fix reacts to the layout after it changes, so the row can be clipped for one frame before
  the scroll lands.

## Release intent

Patch: a small fix to existing behavior. Patch is the default, so the PR carries no release label or directive. Expected version v1.2.1 (estimate until reserved).

## Implementation result

As planned. The layout effect lives in `KeepActiveRowOnScreen` and `bringBackActiveRow` so
`SubtitleTimeline` stays within the detekt size and complexity limits. `SubtitleTimeline` became
`internal` so the instrumented test can compose it on its own. While the panel is dragged shorter,
a fully visible active row now stays in the bottom slot instead of jumping to the top once it
disappears; a row that was only partly visible keeps the old jump-to-top rule.

## Validation result

- Passed locally (Linux, JDK 21, Android SDK 36):
  `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
  `PlaybackArchitectureTest` ran 22 tests with 0 failures, including the new one.
- Not run locally: `pixel2Api36DebugAndroidTest`, because this host has no KVM. CI's
  `managed-device-tests` job runs the new `SubtitleUiTest` case.
- Not run: phone and live YouTube. The owner can check this on the PR's preview APK.
- Final-head CI status is on the PR.
