package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RecordedCaptionStore
import com.kienhoang.dualsubreplay.data.RecordingSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class CaptionRecorderTest {
    private val folder = Files.createTempDirectory("recorder").toFile()
    private val store = RecordedCaptionStore(File(folder, RecordedCaptionStore.FOLDER))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val summaries = ConcurrentHashMap<String, Any>()
    private val video = "x6hXhLNc-jU"

    private fun recorder() = CaptionRecorder(store, scope) { id, summary -> summaries[id] = summary ?: NONE }

    private fun CaptionRecorder.openAndWait(id: String): Any {
        open(id)
        runBlocking { awaitLoaded() }
        return checkNotNull(summaries[id])
    }

    private fun sample(
        time: Long,
        text: String,
        revision: Long,
        videoId: String? = video,
    ) = LiveCaptionSample(text, revision, time, text.isNotBlank(), videoId, "en")

    @After
    fun cleanUp() {
        scope.cancel()
        folder.deleteRecursively()
    }

    @Test
    fun recordsSamplesKeepsThemAndUsesThemOnlyWhenChosen() {
        val first = recorder()
        assertEquals(NONE, first.openAndWait(video))
        first.onSample(video, sample(1_000, "the portal vein", 1), seek = false)
        first.onSample(video, sample(1_500, "the portal vein is patent.", 2), seek = false)
        first.onSample(video, sample(2_000, "ignored", 3, videoId = "U50O8_fsemY"), seek = false)
        first.onSample(video, null, seek = false)
        first.onSample(video, sample(4_000, "", 4), seek = false)
        assertEquals(1, (summaries[video] as RecordingSummary).cues)
        assertNull(store.completeCues(video))

        val used = runBlocking { first.setComplete(video, complete = true) }
        assertTrue(used!!.complete)
        assertEquals(
            "the portal vein is patent.",
            store
                .completeCues(video)
                ?.second
                ?.single()
                ?.text,
        )

        first.close()
        summaries.clear()
        val reopened = recorder().openAndWait(video) as RecordingSummary
        assertTrue(reopened.complete)
        assertEquals(1, reopened.cues)

        runBlocking { first.delete(video) }
        assertEquals(NONE, summaries[video])
        assertNull(store.load(video))
    }

    @Test
    fun aRecordingIsSavedWhenTheVideoChanges() {
        val recorder = recorder()
        recorder.openAndWait(video)
        recorder.onSample(video, sample(0, "hello students", 1), seek = false)
        recorder.open("U50O8_fsemY")
        val deadline = System.currentTimeMillis() + 5_000
        while (store.load(video) == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull(store.load(video))
        assertEquals(
            "hello students",
            store
                .load(video)!!
                .cues()
                .single()
                .text,
        )
    }

    private companion object {
        val NONE = Any()
    }
}
