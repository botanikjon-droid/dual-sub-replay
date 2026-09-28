# Immersion time, Progress screen, and daily goal

## Status

Validated. CI passed on the PR, and the owner tested the preview build on a phone and asked to merge it. Release as v1.2.0. The owner asked for a way to see how much time they spend immersing
in languages with the app (per day, week, month, year and in total) and which languages that time
went to. They chose "option A, daily totals per language", a daily goal set from a new "Progress"
item, and a skippable goal step the first time the app opens, after the guide. That request does
not authorize merging or publishing.

## Context / problem

The app has no record of how long someone has practiced. The drawer offers only Practice and
Settings. Learners who watch a lot of video cannot see their effort, their streak, or the split
between the languages they study.

The app already knows what it needs:

- `SingleYouTubePage` polls the page video and calls `onPlaybackSecond` about every 33 ms with an
  interpolated position that stops advancing while the video is paused or buffering and is not
  reported while it is seeking or stale (`CaptionPlaybackClock.position`).
- `AppViewModel` knows whether playback is paused, whether the app is visible, the resolved caption
  language (`resolvedSourceLanguage`) and the preferred learning language (`sourcePreference`).
- Study data already lives in a local SQLite database (`VocabularyRepository`), and preferences in
  `dual_sub_preferences`.

## Goals

- Count real (wall-clock) time while a YouTube video actually plays in the app, credited to the
  language being learned.
- Store one small row per local day and language, so any period is a simple sum.
- A "Progress" drawer item that shows today against the daily goal, a streak, totals for this week,
  month, year and all time, a bar chart per period, and time per language.
- A daily goal the user can set or clear from Progress.
- A one-time, skippable goal step for new users after the guide.

## Non-goals

- No per-video history, video titles or watch history are stored. Only a per-day video count.
- No backup/export of immersion data in the Practice JSON/Anki transfer (Android backup still
  covers the database like the vocabulary database).
- No reminders or notifications.
- No change to caption discovery, translation, the WebView, replay or highlight timing.
- Saved words have no creation date, so per-language word counts are all-time, not per period.

## User-visible behavior

- **Drawer:** a new "Progress" item between Practice and Settings. Opening it pauses the video, as
  Practice does.
- **Progress screen** (full-screen dialog, like Practice):
  - Today: time watched today and a progress bar towards the daily goal ("12 min of 20 min"), or
    "No daily goal" with a "Set goal" button. A streak line: "3-day streak".
  - Tiles: This week, This month, This year, Total.
  - Period chips Week / Month / Year / All with a bar chart: days of the current week, days of the
    current month, months of the current year, or one bar per year.
  - Languages for the selected period: language name, time, a share bar, videos watched, and the
    all-time number of saved words in that language.
  - Empty state: "Watch a video with captions to start tracking your time."
  - A footnote: time counts while a video plays in the app and is stored only on this device.
- **Daily goal:** choices Off, 5, 10, 15, 20, 30, 45, 60 minutes. Streak = consecutive days that
  reached the goal, ending today or yesterday (today does not break the streak until it is over).
  With no goal, a day counts when it has at least one minute.
- **First launch:** LanguageSetupScreen → GuideScreen → **Daily goal** → main experience. The goal
  step has "Skip" (top right) and "Set goal" (default selection 15 minutes). Skip leaves the goal off.
- **Existing users** who finished the guide before this change do not see the goal step after
  upgrading; they find the goal in Progress. This mirrors the guide migration.

## Technical constraints / invariants

- One WebView only; tracking reuses the existing playback callbacks and adds no JavaScript
  ([invariants](../project/tech-stack.md#architecture-invariants)).
- Preserve guide migration: `guide_completed` handling is untouched. The new
  `daily_goal_prompt_completed` preference follows the same rule: when absent, it inherits
  "guide completed".
- Study data stays local. No network, account or service.
- minSdk 26: SQLite UPSERT (`ON CONFLICT … DO UPDATE`) needs SQLite 3.24 (API 30), so use
  insert-or-ignore then update inside a transaction.
- Unit tests stay plain JUnit4; logic lives in small `internal` functions.

## Proposed approach / plan

1. `data/ImmersionStats.kt` (pure): `ImmersionDay` rows, `ImmersionTimeTracker` (credits wall-clock
   deltas only while media advances, ignores gaps > 1 s and seeks), `ImmersionAccumulator` (pending
   per day/language, counts each video once per day/language, flushes every 30 s), period ranges,
   totals, per-language breakdown, bar buckets, streak, duration formatting.
2. `data/ImmersionRepository.kt`: `immersion.db`, table
   `daily_time(day TEXT, language TEXT, ms INTEGER, videos INTEGER, PRIMARY KEY(day, language))`,
   a `StateFlow` of rows, and a fire-and-forget `record()` on its own IO scope so writes survive
   `onCleared`.
3. `AppViewModel`: feed each playback tick into the tracker with the resolved caption language (or
   the explicit learning preference), flush on pause, when the app is hidden, when the video
   closes, and on `onCleared`. Add `dailyGoalMinutes` and `dailyGoalPromptCompleted` state.
4. UI: `ui/ProgressScreen.kt` (screen + reusable goal picker), `ui/DailyGoalSetupScreen.kt`, a
   "Progress" drawer item, and the new first-launch step in `DualSubApp`.
5. Tests: unit tests for the pure logic and the migration rule; instrumented tests for the
   repository, the Progress content and the goal step.

## Acceptance criteria

- [ ] Playing a video for N seconds with captions in language L adds about N seconds to today's L
      row; paused, buffering, seeking, hidden-app and no-video time adds nothing.
- [ ] A seek jump or a long gap between ticks is not credited as watched time.
- [ ] Time is split at local midnight; each video counts once per day and language.
- [ ] Progress shows today vs goal, streak, week/month/year/total tiles, a bar chart per period
      and a per-language list for the selected period; an empty database shows the empty state.
- [ ] The goal can be set and cleared from Progress and persists across restarts.
- [ ] New users see the goal step after the guide; "Skip" leaves the goal off; users who completed
      the guide before upgrading never see it.
- [ ] Existing flows (language setup, guide, Practice, Settings, playback) behave as before.
- [ ] CI parity checks and managed-device tests pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: new `ImmersionStatsTest` (tracker, accumulator, periods, bars, streak, formatting) and goal-prompt migration tests pass | Local + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local + CI |
| Managed-device/emulator | `ImmersionRepositoryTest` (SQLite round trip, accumulation), `ProgressScreenTest`, `DailyGoalSetupScreenTest` pass | CI managed device |
| Physical-device/manual | Owner installs the PR preview: watch a video, open Progress, confirm time and language, set a goal, fresh install shows the goal step | Owner's phone |
| Live YouTube | Covered by the manual scenario above; offline tests cannot establish it | Owner's phone |
| Documentation/process | Spec, tech-stack component list and README feature list updated | This PR |

## Risks / edge cases

- Undercounting a few ticks when the interpolated clock clamps is acceptable; overcounting after
  sleep or seeks is avoided by the 1 s gap limit and the seek check.
- Process death loses at most about 30 s of pending time.
- A reopened video after a process restart may count as a second video that day.
- Time zone or clock changes only affect which local day a delta lands on.

## Release intent

`release:minor`. The owner tested the preview on a phone and asked to merge and release v1.2.0.
Applied as the GitHub label `release:minor` on PR #84 (latest stable release before it: v1.1.2).

## Implementation result

- `data/ImmersionStats.kt`: tracker, accumulator, periods, totals, breakdown, bars, streak,
  formatting, goal parsing and the goal-step migration rule.
- `data/ImmersionRepository.kt`: `immersion.db` with `daily_time(day, language, ms, videos)`.
- `AppViewModel`: tracking in `onWebPlaybackSecond`, flushes on pause, hidden app, closed video and
  `onCleared`; `setDailyGoal` and `completeDailyGoalPrompt`. `completeGuide` stores
  `daily_goal_prompt_completed=false` so the goal step still appears if the app closes before it.
- UI: `ProgressScreen.kt` (with the shared `DailyGoalPicker`), `DailyGoalSetupScreen.kt`, a
  "Progress" drawer item, the new first-launch step in `DualSubApp`, and the scroll-friendly overlay
  stays hidden during the goal step (`LearningPlayerRoot`).
- Deviations: languages use `TranslationLanguages.normalize` so they match saved words. The word
  dialog in `DualSubApp` moved into `SelectedWordDialog` to keep `DualSubApp` under the detekt
  `LongMethod` limit. "Reset all settings" does not clear the daily goal, which is progress data.

## Validation result

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | Passed locally: 322 tests, 0 failures, including 17 in `ImmersionStatsTest`. |
| `formatCheck` (ktlint ratchet) | Passed locally. |
| `complexityCheck` (detekt) | Passed locally. |
| `lintDebug` | Passed locally after reading the week start from `LocalConfiguration` (`NonObservableLocale`). |
| `assembleDebug assembleDebugAndroidTest` | Passed locally. |
| `tools/tests` | Passed locally (29 tests). |
| Managed-device tests (`ImmersionRepositoryTest`, `ProgressScreenTest`, `NavigationRecoveryUiTest`) | Passed in PR CI (`managed-device-tests`, `fdroid-device-tests`); not run locally (no KVM). |
| Physical device / live YouTube | Passed: the owner installed the PR #84 preview on a phone and reported that it works well. |
