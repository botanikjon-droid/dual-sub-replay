package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.BuildConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/*
 * Pure parts of "Dasturni yangilash" (see AppUpdate.kt): which CI build is installed, which one
 * uz-latest holds, and whether the latter is newer.
 */

internal const val UZ_RELEASE_API_URL = "https://api.github.com/repos/botanikjon-droid/dual-sub-replay/releases/tags/uz-latest"
internal const val UZ_APK_URL = "https://github.com/botanikjon-droid/dual-sub-replay/releases/download/uz-latest/DualSub-Replay-uz.apk"

/** The update menu item exists only in the Uzbek build, the one published as uz-latest. */
internal val appUpdateAvailable: Boolean get() = BuildConfig.DISTRIBUTION == "uz"

/** The CI build number in an installed version name such as "1.0.4-uz-build31"; null for other builds. */
internal fun installedUzBuild(versionName: String): Int? =
    Regex("-uz-build(\\d+)")
        .find(versionName)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

/** The published release, as far as the update check needs it. */
internal data class UzRelease(
    val build: Int?,
    val apkUrl: String,
    val publishedAt: String,
)

/** Reads the GitHub release JSON; the build number comes from the notes CI writes ("(run 31)"). */
internal fun parseUzRelease(json: String): UzRelease {
    val root =
        try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IOException("Yangilanish ma’lumotini o‘qib bo‘lmadi.")
        }
    val body = root.optString("body")
    val build =
        Regex("\\(run (\\d+)\\)")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    var apk = UZ_APK_URL
    val assets = root.optJSONArray("assets")
    for (index in 0 until (assets?.length() ?: 0)) {
        val asset = assets?.optJSONObject(index) ?: continue
        if (asset.optString("name").endsWith(".apk")) apk = asset.optString("browser_download_url").ifBlank { UZ_APK_URL }
    }
    return UzRelease(build, apk, root.optString("published_at").take(DATE_LENGTH).replace('T', ' '))
}

private const val DATE_LENGTH = 16

/** What the dialog says: a newer build, the same build, or unknown (then offer the download anyway). */
internal enum class UpdateVerdict { NEWER, CURRENT, UNKNOWN }

internal fun updateVerdict(
    installed: Int?,
    latest: Int?,
): UpdateVerdict =
    when {
        installed == null || latest == null -> UpdateVerdict.UNKNOWN
        latest > installed -> UpdateVerdict.NEWER
        else -> UpdateVerdict.CURRENT
    }
