package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LessonTranslationProgressTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sentences = listOf("The liver is normal.", "No free fluid.")

    private fun progress(fingerprint: String = lessonFingerprint(sentences)) =
        LessonProgress(
            "vid_123-ABC",
            fingerprint,
            mapOf(
                0 to SavedSentence(0, "Jigar normal.", "DeepSeek", "0-1"),
                1 to SavedSentence(1, "Erkin suyuqlik yo‘q.", "Claude", "0-1"),
            ),
        )

    @Test
    fun savedProgressIsReadBackForTheSameCaptions() {
        val files = LessonProgressFiles(File(folder.root, "progress"))
        files.save(progress())
        assertEquals(progress().sentences, files.read("vid_123-ABC", lessonFingerprint(sentences)))
    }

    @Test
    fun otherCaptionsDoNotReuseTheSavedTranslation() {
        val files = LessonProgressFiles(folder.root)
        files.save(progress())
        assertTrue(files.read("vid_123-ABC", lessonFingerprint(sentences + "One more.")).isEmpty())
        assertTrue(files.read("another0001", lessonFingerprint(sentences)).isEmpty())
        assertNotEquals(lessonFingerprint(listOf("a b")), lessonFingerprint(listOf("a", "b")))
    }

    @Test
    fun aDamagedFileIsIgnoredNotFatal() {
        val files = LessonProgressFiles(folder.root)
        File(folder.root, "vid_123-ABC.json").writeText("{\"videoId\": \"vid_123-ABC\", \"sent")
        assertTrue(files.read("vid_123-ABC", lessonFingerprint(sentences)).isEmpty())
    }

    @Test
    fun everySaveReplacesTheFileWholeAndLeavesNoTemporaryFile() {
        val files = LessonProgressFiles(folder.root)
        files.save(progress())
        val more = progress().let { it.copy(sentences = it.sentences + (2 to SavedSentence(2, "Uchinchi.", "DeepSeek", "2-2"))) }
        files.save(more)
        assertEquals(3, files.read("vid_123-ABC", lessonFingerprint(sentences)).size)
        assertFalse(
            folder.root
                .listFiles()
                .orEmpty()
                .any { it.name.endsWith(".tmp") },
        )
        files.delete("vid_123-ABC")
        assertTrue(files.read("vid_123-ABC", lessonFingerprint(sentences)).isEmpty())
    }

    @Test
    fun timestampsAreNeverPartOfTheFile() {
        val json = lessonProgressToJson(progress())
        assertFalse(json.contains("-->"))
        assertFalse(json.contains("startMs"))
        assertEquals(progress(), lessonProgressFromJson(json))
    }
}
