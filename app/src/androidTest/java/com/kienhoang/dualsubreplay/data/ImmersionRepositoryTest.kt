package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class ImmersionRepositoryTest {
    @Test fun addsTimeAndVideosPerDayAndLanguageAndSurvivesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "immersion-${UUID.randomUUID()}.db"
        val today = LocalDate.of(2026, 9, 30)
        val repository = ImmersionRepository(context, name)
        try {
            runBlocking {
                repository.add(listOf(ImmersionDelta(today, "ja", 30_000, 1), ImmersionDelta(today, "en", 5_000, 1)))
                repository.add(listOf(ImmersionDelta(today, "ja", 15_000, 1), ImmersionDelta(today.plusDays(1), "ja", 1_000, 0)))
            }
            assertEquals(
                setOf(
                    ImmersionDay(today, "ja", 45_000, 2),
                    ImmersionDay(today, "en", 5_000, 1),
                    ImmersionDay(today.plusDays(1), "ja", 1_000, 0),
                ),
                repository.days.value.toSet(),
            )
        } finally {
            repository.close()
        }
        val reopened = ImmersionRepository(context, name)
        try {
            runBlocking { reopened.refresh() }
            assertEquals(51_000L, reopened.days.value.sumOf { it.ms })
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
