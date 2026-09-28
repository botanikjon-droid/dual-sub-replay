package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kienhoang.dualsubreplay.data.ImmersionDay
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ProgressScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 30)

    @Test fun showsTodayGoalStreakTotalsAndLanguages() {
        val days =
            listOf(
                ImmersionDay(today, "ja", 12 * 60_000L, 2),
                ImmersionDay(today.minusDays(1), "ja", 25 * 60_000L, 1),
                ImmersionDay(today.minusDays(1), "en", 5 * 60_000L, 1),
                ImmersionDay(LocalDate.of(2025, 6, 1), "en", 90 * 60_000L, 3),
            )
        compose.setContent {
            DualSubTheme {
                ProgressContent(
                    days = days,
                    savedWordsByLanguage = mapOf("ja" to 4),
                    goalMinutes = 20,
                    today = today,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    onGoalChange = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("progress_today").assertTextEquals("12 min")
        compose.onNodeWithTag("progress_goal_text").assertTextEquals("Daily goal: 20 min")
        compose.onNodeWithTag("progress_streak").assertTextEquals("1-day streak")
        compose.onNodeWithTag("progress_empty").assertDoesNotExist()
        compose.onNodeWithText("2 h 12 min").assertExists()
        saveUiEvidence("progress-week")
        compose.onNodeWithTag("progress_language_ja").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 videos · 4 saved words").assertExists()
        compose.onNodeWithTag("progress_language_en").assertExists()
        compose.onNodeWithTag("progress_period_week").performScrollTo()
        compose.onNodeWithTag("progress_period_all").performClick()
        compose.onNodeWithText("4 videos · 0 saved words").assertExists()
        saveUiEvidence("progress-all")
    }

    @Test fun emptyHistoryExplainsHowToStart() {
        compose.setContent {
            DualSubTheme {
                ProgressContent(
                    days = emptyList(),
                    savedWordsByLanguage = emptyMap(),
                    goalMinutes = 0,
                    today = today,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    onGoalChange = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("progress_today").assertTextEquals("0 min")
        compose.onNodeWithTag("progress_goal_text").assertTextEquals("No daily goal")
        compose.onNodeWithTag("progress_streak").assertTextEquals("Start a streak today")
        compose.onNodeWithTag("progress_empty").assertIsDisplayed()
        compose.onNodeWithText("No time in this period yet.").assertExists()
    }

    @Test fun goalCanBeSetAndCleared() {
        val goal = mutableIntStateOf(0)
        val changes = mutableListOf<Int>()
        compose.setContent {
            DualSubTheme {
                ProgressContent(
                    days = listOf(ImmersionDay(today, "ja", 30 * 60_000L, 1)),
                    savedWordsByLanguage = emptyMap(),
                    goalMinutes = goal.intValue,
                    today = today,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    onGoalChange = {
                        changes += it
                        goal.intValue = it
                    },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("progress_edit_goal").performClick()
        compose.onNodeWithTag("daily_goal_30").performClick()
        compose.onNodeWithTag("daily_goal_save").performClick()
        compose.onNodeWithTag("progress_goal_text").assertTextEquals("Daily goal of 30 min reached")
        compose.onNodeWithTag("progress_edit_goal").performClick()
        compose.onNodeWithTag("daily_goal_0").performClick()
        compose.onNodeWithTag("daily_goal_save").performClick()
        compose.onNodeWithTag("progress_goal_text").assertTextEquals("No daily goal")
        compose.runOnIdle { assertEquals(listOf(30, 0), changes) }
    }

    @Test fun firstLaunchGoalStepCanBeSetOrSkipped() {
        var result: Int? = -1
        compose.setContent {
            DualSubTheme { DailyGoalSetupScreen(onFinish = { result = it }) }
        }
        compose.onNodeWithTag("daily_goal_setup").assertIsDisplayed()
        saveUiEvidence("daily-goal-setup")
        compose.onNodeWithTag("daily_goal_skip").performClick()
        compose.runOnIdle { assertNull(result) }
    }

    @Test fun firstLaunchGoalStepReturnsTheChosenMinutes() {
        var result: Int? = null
        compose.setContent {
            DualSubTheme { DailyGoalSetupScreen(onFinish = { result = it }) }
        }
        compose.onNodeWithTag("daily_goal_30").performClick()
        compose.onNodeWithTag("daily_goal_confirm").performClick()
        compose.runOnIdle { assertEquals(30, result) }
    }
}
