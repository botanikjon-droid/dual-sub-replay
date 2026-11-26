package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.LessonProgress
import com.kienhoang.dualsubreplay.data.LessonProgressFiles
import com.kienhoang.dualsubreplay.data.SavedSentence
import com.kienhoang.dualsubreplay.data.chunkIdOf
import com.kienhoang.dualsubreplay.data.lessonFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LessonResumeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val lesson = (0 until 12).map { "sentence $it" }

    /** Translates every chunk it gets ("uz N"), records which sentences were sent, and can fail on a chunk. */
    private class RecordingBackend(
        private val failAtCall: Int = -1,
    ) : LessonBackend {
        val sent = mutableListOf<List<Int>>()

        override suspend fun translate(
            userPrompt: String,
            chunk: ClaudeChunk,
        ): ClaudeChunkResult {
            if (sent.size == failAtCall) throw TranslationStoppedException("DeepSeek hisobida mablag‘ tugagan (HTTP 402).")
            val indices = chunk.sentences.indices.map { chunk.start + it }
            sent += indices
            return ClaudeChunkResult(indices.associateWith { "uz $it" }, emptyList(), emptyList(), "DeepSeek")
        }
    }

    @Test
    fun missingChunksSkipDoneSentencesAndKeepIndices() {
        val chunks = missingChunks(lesson, setOf(0, 1, 2, 6), size = 2)
        assertEquals(listOf(3, 5, 7, 9, 11), chunks.map { it.start })
        assertEquals(listOf(listOf("sentence 3", "sentence 4"), listOf("sentence 5")), chunks.take(2).map { it.sentences })
        assertEquals(claudeChunks(lesson, 5), missingChunks(lesson, emptySet(), 5))
    }

    @Test
    fun everyFinishedChunkIsSavedAtOnceAndAResumedRunSendsOnlyTheRest() {
        val files = LessonProgressFiles(folder.newFolder("progress"))
        val fingerprint = lessonFingerprint(lesson)
        val saved = linkedMapOf<Int, SavedSentence>()

        suspend fun run(
            backend: RecordingBackend,
            done: Map<Int, String>,
        ) = LessonTranslator(backend, chunkSize = 4).translate(lesson, { emptyList() }, { emptyList() }, done) { chunk, _ ->
            chunk.translations.forEach { (i, uz) -> saved[i] = SavedSentence(i, uz, chunk.engine, chunkIdOf(chunk.translations.keys)) }
            files.save(LessonProgress("abcdefghijk", fingerprint, saved))
        }

        // First run: chunk 0-3 succeeds, then the balance runs out on chunk 4-7.
        val first = RecordingBackend(failAtCall = 1)
        try {
            runBlocking { run(first, emptyMap()) }
            fail("the run must stop")
        } catch (expected: TranslationStoppedException) {
            assertTrue(expected.message.orEmpty().contains("402"))
        }
        val afterStop = files.read("abcdefghijk", fingerprint)
        assertEquals((0..3).toSet(), afterStop.keys)
        assertEquals("0-3", afterStop.getValue(2).chunkId)
        assertEquals("DeepSeek", afterStop.getValue(2).provider)

        // The lesson is opened again: only sentences 4-11 are sent.
        val second = RecordingBackend()
        val result = runBlocking { run(second, afterStop.mapValues { it.value.uzbek }) }
        assertEquals(listOf((4..7).toList(), (8..11).toList()), second.sent)
        assertEquals(lesson.indices.toList(), result.translations.keys.sorted())
        assertEquals("uz 0", result.translations[0])
        assertEquals(lesson.indices.toSet(), files.read("abcdefghijk", fingerprint).keys)
    }

    @Test
    fun aFullySavedLessonSendsNothing() {
        val backend = RecordingBackend()
        val done = lesson.indices.associateWith { "uz $it" }
        val result = runBlocking { LessonTranslator(backend).translate(lesson, { emptyList() }, { emptyList() }, done) { _, _ -> } }
        assertTrue(backend.sent.isEmpty())
        assertEquals(done, result.translations)
    }

    @Test
    fun savedSentencesAreGivenAsContextToTheNextChunk() {
        var prompt = ""
        val backend =
            LessonBackend { userPrompt, chunk ->
                prompt = userPrompt
                ClaudeChunkResult(chunk.sentences.indices.associate { chunk.start + it to "x" }, emptyList(), emptyList())
            }
        runBlocking {
            LessonTranslator(
                backend,
                chunkSize = 30,
            ).translate(lesson, { emptyList() }, { emptyList() }, mapOf(0 to "birinchi")) { _, _ -> }
        }
        assertTrue(prompt.contains("birinchi"))
        assertTrue(!prompt.contains("\"i\":0"))
    }
}
