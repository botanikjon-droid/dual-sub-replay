package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class GeminiBatchSchedulerTest {
    private val video = (1..80).map { "sentence $it" }

    /** A fake Gemini: stores "uz: <sentence>" for every sentence of a batch after [latencyMs]. */
    private class FakeGemini(
        private val latencyMs: (SentenceBatch) -> Long,
        private val failure: IOException? = null,
    ) {
        val store = ConcurrentHashMap<String, String>()
        val batches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

        suspend fun fetch(batch: SentenceBatch) {
            batches += batch.sentences
            delay(latencyMs(batch))
            failure?.let { throw it }
            batch.sentences.forEach { store[it] = "uz: $it" }
        }
    }

    private fun scheduler(
        gemini: FakeGemini,
        scope: CoroutineScope,
        waitMs: Long,
    ) = GeminiBatchScheduler(scope, { video }, { gemini.store.containsKey(it) }, gemini::fetch, waitMs = waitMs)

    @Test
    fun aQuickSmallBatchAnswersAndTheNextBatchIsFetchedAhead() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gemini = FakeGemini({ 20 })
            val scheduler = scheduler(gemini, scope, waitMs = 2_000)
            assertTrue(scheduler.awaitTranslation("sentence 1"))
            assertEquals("uz: sentence 1", gemini.store["sentence 1"])
            // The waiting request is small; the one running ahead is a full batch right after it.
            withTimeout(2_000) { while (gemini.batches.size < 2) delay(5) }
            val batches = gemini.batches.toList()
            assertTrue((1..GEMINI_FIRST_BATCH_SIZE).map { "sentence $it" } in batches)
            val ahead = batches.single { it.first() == "sentence ${GEMINI_FIRST_BATCH_SIZE + 1}" }
            assertEquals(GEMINI_BATCH_SIZE, ahead.size)
            scope.cancel()
        }

    @Test
    fun aSlowGeminiNeverHoldsPlaybackLongerThanTheWait() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gemini = FakeGemini({ 1_000 })
            val scheduler = scheduler(gemini, scope, waitMs = 100)
            val started = System.currentTimeMillis()
            assertFalse(scheduler.awaitTranslation("sentence 1"))
            val waited = System.currentTimeMillis() - started
            assertTrue("waited $waited ms", waited < 800)
            // The batch keeps going in the background and fills the later sentences.
            withTimeout(3_000) { while (!gemini.store.containsKey("sentence 2")) delay(10) }
            assertTrue(scheduler.awaitTranslation("sentence 2"))
            scope.cancel()
        }

    @Test
    fun aSentenceAlreadyOnItsWayIsNotRequestedAgain() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gemini = FakeGemini({ 300 })
            val scheduler = scheduler(gemini, scope, waitMs = 2_000)
            assertTrue(scheduler.awaitTranslation("sentence 1"))
            // Sentence 3 was in the first batch; sentence 10 is in the batch running ahead.
            assertTrue(scheduler.awaitTranslation("sentence 3"))
            assertTrue(scheduler.awaitTranslation("sentence 10"))
            val requested = gemini.batches.flatten()
            assertEquals(requested.size, requested.toSet().size)
            scope.cancel()
        }

    @Test
    fun aGeminiErrorReachesTheCallerSoItFallsBack() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gemini = FakeGemini({ 10 }, failure = IOException("HTTP 429 - quota"))
            val scheduler = scheduler(gemini, scope, waitMs = 2_000)
            try {
                scheduler.awaitTranslation("sentence 1")
                fail("Expected the Gemini error")
            } catch (error: IOException) {
                assertEquals("HTTP 429 - quota", error.message)
            }
            scope.cancel()
        }
}
