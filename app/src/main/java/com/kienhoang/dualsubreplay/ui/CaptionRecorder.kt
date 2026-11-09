package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionRecording
import com.kienhoang.dualsubreplay.data.RecordedCaptionStore
import com.kienhoang.dualsubreplay.data.RecordingSummary
import com.kienhoang.dualsubreplay.data.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records the captions the YouTube page shows while a video without a downloadable caption track plays (live
 * fallback), and keeps the recording on the phone. Called on the main thread; files are written on IO.
 */
internal class CaptionRecorder(
    val store: RecordedCaptionStore,
    private val scope: CoroutineScope,
    private val onSummary: (videoId: String, summary: RecordingSummary?) -> Unit,
) {
    private var videoId: String? = null
    private var recording: CaptionRecording? = null
    private var loaded = false
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var dirty = false

    /** Loads [id]'s earlier recording (if any), saving the previous video's first. Call after [id] became active. */
    fun open(id: String) {
        if (videoId == id) return
        close()
        videoId = id
        onSummary(id, null)
        loadJob =
            scope.launch {
                val earlier = withContext(Dispatchers.IO) { runCatching { store.load(id) }.getOrNull() }
                if (videoId != id) return@launch
                recording = earlier
                loaded = true
                onSummary(id, earlier?.takeUnless { it.isEmpty }?.summary())
            }
    }

    /** Waits until the open video's earlier recording is read. */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    /** One page sample in live fallback. A missing sample (captions not captured) ends the current pass. */
    fun onSample(
        id: String,
        sample: LiveCaptionSample?,
        seek: Boolean,
    ) {
        if (id != videoId || !loaded) return
        if (sample == null || (sample.videoId != null && sample.videoId != id)) {
            recording?.endPass()
            return
        }
        val target = recording ?: CaptionRecording(id).also { recording = it }
        val changed = target.record(sample.mediaTimeMs, sample.text, sample.present, sample.revision, sample.languageCode, seek)
        if (!changed) return
        dirty = true
        onSummary(id, target.summary())
        scheduleSave()
    }

    /** Marks the open video's recording as the lesson's subtitles (or not) and waits until it is saved. */
    suspend fun setComplete(
        id: String,
        complete: Boolean,
    ): RecordingSummary? {
        awaitLoaded()
        val target = recording?.takeIf { videoId == id } ?: withContext(Dispatchers.IO) { store.load(id) } ?: return null
        target.endPass()
        target.complete = complete
        val text = target.encode()
        withContext(Dispatchers.IO) { store.save(id, text) }
        if (videoId == id) dirty = false
        return target.summary().also { onSummary(id, it) }
    }

    suspend fun delete(id: String) {
        if (videoId == id) {
            saveJob?.cancel()
            recording = null
            dirty = false
        }
        withContext(Dispatchers.IO) { store.delete(id) }
        onSummary(id, null)
    }

    /** Saves and forgets the open video's recording (another video opens, or none). */
    fun close() {
        loadJob?.cancel()
        saveJob?.cancel()
        val id = videoId
        val text = recording?.takeIf { dirty }?.also { it.endPass() }?.encode()
        if (id != null && text != null) scope.launch(NonCancellable + Dispatchers.IO) { runCatching { store.save(id, text) } }
        videoId = null
        recording = null
        loaded = false
        dirty = false
    }

    /** For onCleared, when the scope no longer runs: writes on the calling thread. */
    fun saveNow() {
        val id = videoId ?: return
        val text = recording?.takeIf { dirty }?.encode() ?: return
        runCatching { store.save(id, text) }
        dirty = false
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob =
            scope.launch {
                delay(SAVE_DELAY_MS)
                val id = videoId ?: return@launch
                val text = recording?.takeIf { dirty }?.encode() ?: return@launch
                dirty = false
                withContext(Dispatchers.IO) { runCatching { store.save(id, text) } }
                if (dirty && videoId == id) scope.launch { scheduleSave() }
            }
    }

    private companion object {
        const val SAVE_DELAY_MS = 3_000L
    }
}
