package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/*
 * "API sarfi": one line per paid API call (DeepSeek, Claude), kept in the app's private files so it survives
 * a restart. Only what the provider's reply says is stored: tokens it did not report stay 0 with
 * tokensReported=false, so they are never shown as real spending. Keys and request headers are never logged.
 */

internal data class ApiUsageEntry(
    val timeMs: Long,
    /** The lesson's video id; empty for calls outside a lesson run (chat, notes, sentence checks). */
    val lessonId: String,
    /** "first-last" sentence index of the chunk; empty when the call had no chunk. */
    val chunkId: String,
    val provider: String,
    val model: String,
    /** Input tokens billed at the normal price (cache reads and writes are counted separately). */
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    /** False when the reply carried no usage numbers: the zeros above are unknown, not free. */
    val tokensReported: Boolean,
    /** HTTP status ("200", "402" …) or the failure kind ("network", "bad_reply"). */
    val status: String,
    /** True when Claude answered because DeepSeek's reply was unusable. */
    val fallback: Boolean,
    val success: Boolean,
)

internal fun apiUsageToJson(entry: ApiUsageEntry): String =
    JSONObject()
        .put("t", entry.timeMs)
        .put("lesson", entry.lessonId)
        .put("chunk", entry.chunkId)
        .put("provider", entry.provider)
        .put("model", entry.model)
        .put("in", entry.inputTokens)
        .put("out", entry.outputTokens)
        .put("cache_read", entry.cacheReadTokens)
        .put("cache_write", entry.cacheWriteTokens)
        .put("reported", entry.tokensReported)
        .put("status", entry.status)
        .put("fallback", entry.fallback)
        .put("ok", entry.success)
        .toString()

/** One stored line; null for a line cut short by a crash or written by a newer version. */
internal fun apiUsageFromJson(line: String): ApiUsageEntry? =
    try {
        val json = JSONObject(line)
        ApiUsageEntry(
            timeMs = json.getLong("t"),
            lessonId = json.optString("lesson"),
            chunkId = json.optString("chunk"),
            provider = json.getString("provider"),
            model = json.optString("model"),
            inputTokens = json.optLong("in"),
            outputTokens = json.optLong("out"),
            cacheReadTokens = json.optLong("cache_read"),
            cacheWriteTokens = json.optLong("cache_write"),
            tokensReported = json.optBoolean("reported"),
            status = json.optString("status"),
            fallback = json.optBoolean("fallback"),
            success = json.optBoolean("ok"),
        )
    } catch (error: JSONException) {
        null
    }

internal data class ApiUsageTotals(
    val calls: Int,
    val failed: Int,
    val fallbacks: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    /** Calls whose reply had no token numbers (their tokens are not in the sums). */
    val unreported: Int,
)

/** Sums of [entries], of one lesson when [lessonId] is given. */
internal fun apiUsageTotals(
    entries: List<ApiUsageEntry>,
    lessonId: String? = null,
): ApiUsageTotals {
    val chosen = if (lessonId == null) entries else entries.filter { it.lessonId == lessonId }
    return ApiUsageTotals(
        calls = chosen.size,
        failed = chosen.count { !it.success },
        fallbacks = chosen.count { it.fallback },
        inputTokens = chosen.sumOf { it.inputTokens },
        outputTokens = chosen.sumOf { it.outputTokens },
        cacheReadTokens = chosen.sumOf { it.cacheReadTokens },
        cacheWriteTokens = chosen.sumOf { it.cacheWriteTokens },
        unreported = chosen.count { !it.tokensReported },
    )
}

/**
 * The log file: JSON lines, appended one call at a time (a line cut by a crash is skipped when read).
 * When it grows past [maxEntries] the oldest lines are dropped in one atomic rewrite.
 */
internal class ApiUsageFile(
    private val file: File,
    private val maxEntries: Int = MAX_STORED_CALLS,
) {
    fun readAll(): List<ApiUsageEntry> = if (file.isFile) file.readLines(Charsets.UTF_8).mapNotNull(::apiUsageFromJson) else emptyList()

    fun append(entry: ApiUsageEntry) {
        file.parentFile?.mkdirs()
        file.appendText(apiUsageToJson(entry) + "\n", Charsets.UTF_8)
        val entries = readAll()
        if (entries.size > maxEntries) {
            writeFileAtomically(file, entries.takeLast(maxEntries).joinToString("") { apiUsageToJson(it) + "\n" })
        }
    }

    companion object {
        const val MAX_STORED_CALLS = 2_000
    }
}

internal object ApiUsageLog {
    private val _entries = MutableStateFlow<List<ApiUsageEntry>>(emptyList())
    val entries: StateFlow<List<ApiUsageEntry>> = _entries
    private val lock = Mutex()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: ApiUsageFile? = null

    suspend fun load(context: Context) =
        lock.withLock {
            if (file != null) return@withLock
            val log = ApiUsageFile(File(context.applicationContext.filesDir, LOG_FILE))
            file = log
            val stored =
                withContext(Dispatchers.IO) {
                    try {
                        log.readAll()
                    } catch (error: IOException) {
                        Log.w(TAG, "API usage log not read", error)
                        emptyList()
                    }
                }
            _entries.update { (stored + it).takeLast(ApiUsageFile.MAX_STORED_CALLS) }
        }

    /** Adds one call; the file is written in the background, never on the caller's thread. */
    fun record(entry: ApiUsageEntry) {
        _entries.update { (it + entry).takeLast(ApiUsageFile.MAX_STORED_CALLS) }
        writer.launch {
            lock.withLock {
                try {
                    file?.append(entry)
                } catch (error: IOException) {
                    Log.w(TAG, "API usage not saved", error)
                }
            }
        }
    }

    private const val LOG_FILE = "api-usage.jsonl"
    private const val TAG = "ApiUsageLog"
}

/** One line for the lesson screen; tokens the providers did not report are named as unknown, not counted. */
internal fun apiUsageLine(totals: ApiUsageTotals): String =
    buildString {
        append("API sarfi (shu dars): ${totals.calls} so‘rov")
        if (totals.failed > 0) append(", ${totals.failed} xato")
        if (totals.fallbacks > 0) append(", ${totals.fallbacks} Claude zaxira")
        append(". Token: kirish ${totals.inputTokens}, chiqish ${totals.outputTokens}")
        if (totals.cacheReadTokens > 0 || totals.cacheWriteTokens > 0) {
            append(", keshdan o‘qildi ${totals.cacheReadTokens}, keshga yozildi ${totals.cacheWriteTokens}")
        }
        append('.')
        if (totals.unreported > 0) append(" ${totals.unreported} ta so‘rovda token soni noma’lum (xato javob).")
    }
