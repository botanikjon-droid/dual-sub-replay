package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** How long playback waits for Gemini on one sentence before Google Translate takes it. */
internal const val GEMINI_WAIT_MS = 6_000L

/** At most this many Gemini requests at once: one the video waits for, one running ahead. */
internal const val GEMINI_MAX_REQUESTS = 2

/**
 * Decides which Gemini batches run, so playback never waits on a long background request.
 *
 * A sentence the video needs now starts a small batch (or joins the batch already fetching it) and is
 * waited for at most [waitMs]; meanwhile the following sentences are fetched ahead in larger batches.
 * [fetch] translates one batch and stores the results; [isTranslated] reads that store.
 */
internal class GeminiBatchScheduler(
    private val scope: CoroutineScope,
    private val sentences: () -> List<String>,
    private val isTranslated: (String) -> Boolean,
    private val fetch: suspend (SentenceBatch) -> Unit,
    private val waitMs: Long = GEMINI_WAIT_MS,
    maxRequests: Int = GEMINI_MAX_REQUESTS,
) {
    private val permits = Semaphore(maxRequests)
    private val inFlight = HashMap<String, Deferred<Unit>>()

    /**
     * Waits (at most [waitMs]) until [text] is translated. False when Gemini is too slow, so the caller
     * uses another translator for this sentence; a Gemini error is thrown as the fetch threw it.
     */
    suspend fun awaitTranslation(text: String): Boolean {
        if (isTranslated(text)) {
            prefetchAfter(text)
            return true
        }
        val job = running(text) ?: start(text, GEMINI_FIRST_BATCH_SIZE)
        prefetchAfter(text)
        val finished = withTimeoutOrNull(waitMs) { job.await() } != null
        return finished && isTranslated(text)
    }

    /** Starts the batch after [text] when one of the coming sentences is neither translated nor on its way. */
    fun prefetchAfter(text: String) {
        val next = sentencesAfter(sentences(), text).firstOrNull { !isTranslated(it) && running(it) == null } ?: return
        start(next, GEMINI_BATCH_SIZE)
    }

    private fun running(text: String): Deferred<Unit>? = synchronized(inFlight) { inFlight[text]?.takeIf { it.isActive } }

    private fun start(
        text: String,
        size: Int,
    ): Deferred<Unit> {
        val batch = sentenceBatch(sentences(), text, size = size) { isTranslated(it) || running(it) != null }
        val job = scope.async(start = CoroutineStart.LAZY) { permits.withPermit { fetch(batch) } }
        // Registered before it starts, so a quick batch cannot finish before it is listed.
        synchronized(inFlight) { batch.sentences.forEach { inFlight[it] = job } }
        job.invokeOnCompletion {
            synchronized(inFlight) { batch.sentences.forEach { if (inFlight[it] === job) inFlight.remove(it) } }
        }
        job.start()
        return job
    }
}
