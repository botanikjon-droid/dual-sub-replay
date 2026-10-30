package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/*
 * A lesson translation in progress, saved after every chunk. A run that stops (an API error, the app closed)
 * keeps what was already paid for: the next run sends only the sentences that have no translation yet.
 * The sentences are identified by their index in the lesson; the caption timestamps stay with the app and
 * are never part of this file. A fingerprint of the English sentences makes sure a saved translation is
 * only reused for the same captions.
 */

internal data class SavedSentence(
    val index: Int,
    val uzbek: String,
    /** The provider that really translated it ("DeepSeek", "Claude", "Gemini"). */
    val provider: String,
    /** "first-last" sentence index of the chunk it came in. */
    val chunkId: String,
)

internal data class LessonProgress(
    val videoId: String,
    val fingerprint: String,
    val sentences: Map<Int, SavedSentence>,
)

/** Changes whenever the lesson's English sentences change (another caption track, edited captions). */
internal fun lessonFingerprint(sentences: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(sentences.size.toString().toByteArray(Charsets.UTF_8))
    sentences.forEach { sentence ->
        digest.update(0)
        digest.update(sentence.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun chunkIdOf(indices: Collection<Int>): String = if (indices.isEmpty()) "" else "${indices.min()}-${indices.max()}"

internal fun lessonProgressToJson(progress: LessonProgress): String {
    val items = JSONArray()
    progress.sentences.values.sortedBy(SavedSentence::index).forEach {
        items.put(
            JSONObject()
                .put("i", it.index)
                .put("uz", it.uzbek)
                .put("provider", it.provider)
                .put("chunk", it.chunkId),
        )
    }
    return JSONObject()
        .put("version", 1)
        .put("videoId", progress.videoId)
        .put("fingerprint", progress.fingerprint)
        .put("sentences", items)
        .toString()
}

/** The saved progress, or null when the text is not a progress file. */
internal fun lessonProgressFromJson(text: String): LessonProgress? =
    try {
        val json = JSONObject(text)
        val items = json.getJSONArray("sentences")
        val sentences = linkedMapOf<Int, SavedSentence>()
        for (position in 0 until items.length()) {
            val item = items.getJSONObject(position)
            val uzbek = item.getString("uz")
            if (uzbek.isBlank()) continue
            val index = item.getInt("i")
            sentences[index] = SavedSentence(index, uzbek, item.optString("provider"), item.optString("chunk"))
        }
        LessonProgress(json.getString("videoId"), json.getString("fingerprint"), sentences)
    } catch (error: JSONException) {
        null
    }

/** One file per lesson in [directory]; every write replaces the file atomically. */
internal class LessonProgressFiles(
    private val directory: File,
) {
    private fun fileOf(videoId: String): File = File(directory, videoId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Sentences already translated for these captions; empty when nothing usable is saved. */
    fun read(
        videoId: String,
        fingerprint: String,
    ): Map<Int, SavedSentence> {
        val file = fileOf(videoId)
        if (!file.isFile) return emptyMap()
        val progress =
            try {
                lessonProgressFromJson(file.readText(Charsets.UTF_8))
            } catch (error: IOException) {
                null
            } ?: return emptyMap()
        return if (progress.videoId == videoId && progress.fingerprint == fingerprint) progress.sentences else emptyMap()
    }

    @Throws(IOException::class)
    fun save(progress: LessonProgress) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Oraliq tarjima papkasi yaratilmadi.")
        writeFileAtomically(fileOf(progress.videoId), lessonProgressToJson(progress))
    }

    /** Called once the whole lesson is stored as its translation. */
    fun delete(videoId: String) {
        fileOf(videoId).delete()
    }
}

/**
 * Writes [text] to a temporary file next to [target], flushes it to the disk and moves it over [target]
 * in one step, so a crash leaves either the old file or the new one, never half of one.
 */
@Throws(IOException::class)
internal fun writeFileAtomically(
    target: File,
    text: String,
) {
    val temporary = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(temporary).use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.fd.sync()
    }
    try {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Fayl saqlanmadi: ${target.name}")
        }
    }
}
