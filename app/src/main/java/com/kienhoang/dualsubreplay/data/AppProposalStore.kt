package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A fault or a wish about the app, written down in SHOKALON chat for the developer. */
internal data class AppProposal(
    /** "xato" or "taklif". */
    val kind: String,
    val title: String,
    val details: String,
    val atMs: Long,
)

internal fun appProposalsToJson(items: List<AppProposal>): String =
    JSONArray()
        .also { array ->
            items.forEach {
                array.put(
                    JSONObject()
                        .put("kind", it.kind)
                        .put("title", it.title)
                        .put("details", it.details)
                        .put("at", it.atMs),
                )
            }
        }.toString()

internal fun appProposalsFromJson(text: String): List<AppProposal> =
    try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                AppProposal(it.optString("kind", "taklif"), it.optString("title"), it.optString("details"), it.optLong("at"))
            }
        }
    } catch (error: JSONException) {
        emptyList()
    }

/** The list as text to share with the developer. */
internal fun appProposalsText(items: List<AppProposal>): String =
    buildString {
        append("SHOKALON — dastur bo‘yicha xatolar va takliflar (").append(items.size).append(" ta)\n\n")
        val day = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)
        items.forEachIndexed { index, item ->
            append(index + 1)
                .append(". [")
                .append(item.kind)
                .append("] ")
                .append(item.title)
            append(" (").append(day.format(Date(item.atMs))).append(")\n")
            append(item.details.trim()).append("\n\n")
        }
    }

/** filesDir/app-proposals.json: the doctor's list, kept until he clears it. */
internal object AppProposalStore {
    private val _items = MutableStateFlow<List<AppProposal>>(emptyList())
    val items: StateFlow<List<AppProposal>> = _items
    private var file: File? = null

    suspend fun load(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        _items.value =
            withContext(Dispatchers.IO) {
                try {
                    if (target.isFile) appProposalsFromJson(target.readText(Charsets.UTF_8)) else emptyList()
                } catch (error: IOException) {
                    Log.w(TAG, "Proposals not read", error)
                    emptyList()
                }
            }
    }

    suspend fun add(item: AppProposal) = save(_items.value + item)

    suspend fun clear() = save(emptyList())

    private suspend fun save(next: List<AppProposal>) {
        _items.value = next
        val target = file ?: return
        withContext(Dispatchers.IO) {
            try {
                target.writeText(appProposalsToJson(next), Charsets.UTF_8)
            } catch (error: IOException) {
                Log.w(TAG, "Proposals not saved", error)
            }
        }
    }

    private const val FILE_NAME = "app-proposals.json"
    private const val TAG = "AppProposalStore"
}
