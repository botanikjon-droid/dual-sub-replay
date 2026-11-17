package com.kienhoang.dualsubreplay.poliglot

import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/*
 * "Poliglot": the 30-day English (B1 speaking) and Arabic (from zero) course, a page in assets/poliglot/.
 * Its course content is assets/poliglot_content.json; progress lives in files/poliglot-v2.json (the old course:
 * files/poliglot-state.json) and the doctor's own recordings in files/poliglot-rec/, all only on the phone.
 */

internal const val POLIGLOT_STATE_FILE = "poliglot-state.json"
internal const val POLIGLOT_RECORDINGS_DIR = "poliglot-rec"
internal const val POLIGLOT_PREFS = "poliglot"
private const val RECORDING_SUFFIX = ".m4a"
private const val MAX_TAG_LENGTH = 40

/** A recording's file name: its start time, then the exercise tag with only letters, digits and dashes. */
internal fun poliglotRecordingName(
    nowMillis: Long,
    tag: String?,
): String {
    val safe =
        (tag?.takeIf(String::isNotBlank) ?: "yozuv")
            .replace(Regex("[^A-Za-z0-9-]"), "-")
            .take(MAX_TAG_LENGTH)
    return "${nowMillis}_$safe$RECORDING_SUFFIX"
}

/** Only a plain file name of a recording may be played or deleted: no folders, no way out of the folder. */
internal fun isPoliglotRecordingName(name: String?): Boolean =
    !name.isNullOrEmpty() &&
        name.endsWith(RECORDING_SUFFIX) &&
        '/' !in name &&
        '\\' !in name &&
        ".." !in name

/** The next moment the daily reminder rings: today at [hour]:[minute] if that is still ahead, else tomorrow. */
internal fun nextPoliglotReminder(
    nowMillis: Long,
    hour: Int,
    minute: Int,
    zone: TimeZone = TimeZone.getDefault(),
): Long {
    val calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}

/** The reminder time the page sends is kept only when it is a real clock time. */
internal fun isPoliglotClockTime(
    hour: Int,
    minute: Int,
): Boolean = hour in 0..23 && minute in 0..59

/** A name the page may keep its progress under: files/<name>.json, letters, digits and dashes only. */
internal fun isPoliglotDataName(name: String?): Boolean = name != null && Regex("[a-z0-9-]{1,40}").matches(name)

internal const val CLAUDE_PACKAGE = "com.anthropic.claude"

/** A new Claude chat with [prompt] already typed in. */
internal fun claudeNewChatUrl(prompt: String): String = "https://claude.ai/new?q=" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
