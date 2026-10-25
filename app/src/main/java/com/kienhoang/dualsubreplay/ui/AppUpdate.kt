package com.kienhoang.dualsubreplay.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * "Dasturni yangilash" for the Uzbek build. CI publishes each Uzbek build as the uz-latest
 * pre-release of the doctor's fork; its notes name the CI run ("(run 31)"), and the installed build's
 * version name ends with the same number ("-uz-build31"). The dialog compares the two and hands the APK's
 * link to Android's own download service (the browser's in-app tab could stop at 100 % without saving the
 * file); its "download complete" notification opens Android's installer. The app itself never downloads or
 * installs APKs (no install permission), so it does not look like an app installer. Only public release
 * data is read; nothing is sent.
 */

private suspend fun fetchUzRelease(): UzRelease =
    withContext(Dispatchers.IO) {
        val connection = URL(UZ_RELEASE_API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DualSubReplay-uz")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub javob bermadi (HTTP $code).")
            parseUzRelease(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

private const val TIMEOUT_MS = 15_000

private fun openInBrowser(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: ActivityNotFoundException) {
        false
    }

/**
 * Gives the APK to Android's download service (Downloads folder, a notification that opens the installer
 * when done). Android 9 and older would need a storage permission for that, so there the browser opens.
 */
private fun startDownload(
    context: Context,
    url: String,
    build: Int?,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return openInBrowser(context, url)
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return openInBrowser(context, url)
    val name = "SHOKALON-build${build ?: "yangi"}.apk"
    val request =
        DownloadManager
            .Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("SHOKALON yangilanishi: tugagach bosing va \u201CO\u2018rnatish\u201Dni tanlang")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    return try {
        manager.enqueue(request)
        true
    } catch (error: IllegalStateException) {
        openInBrowser(context, url)
    } catch (error: IllegalArgumentException) {
        openInBrowser(context, url)
    }
}

private const val APK_MIME = "application/vnd.android.package-archive"

/** Checks uz-latest and starts the new APK's download. */
@Composable
internal fun AppUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed = remember { installedUzBuild(BuildConfig.VERSION_NAME) }
    var release by remember { mutableStateOf<UzRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            release = fetchUzRelease()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: IOException) {
            error = failure.message ?: "Internetga ulanib bo‘lmadi."
        }
    }
    val verdict = release?.let { updateVerdict(installed, it.build) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app_update_dialog"),
        title = { Text("Dasturni yangilash") },
        text = { UpdateDialogBody(release, error, verdict, opened) },
        confirmButton = {
            TextButton(
                enabled = release != null || error != null,
                modifier = Modifier.testTag("download_update"),
                onClick = {
                    opened = startDownload(context, release?.apkUrl ?: UZ_APK_URL, release?.build)
                    if (!opened) error = "Yuklab olishni boshlab bo\u2018lmadi."
                },
            ) { Text("Yuklab olish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Yopish") } },
    )
}

/** The dialog's text: versions, verdict, what to do after the download and the last error. */
@Composable
private fun UpdateDialogBody(
    release: UzRelease?,
    error: String?,
    verdict: UpdateVerdict?,
    opened: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("O‘rnatilgan versiya: ${BuildConfig.VERSION_NAME}")
        when {
            release == null && error == null ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Yangi versiya tekshirilmoqda…")
                }
            release != null -> {
                Text("Eng oxirgi versiya: build ${release.build ?: "?"} (${release.publishedAt} UTC)")
                Text(
                    when (verdict) {
                        UpdateVerdict.NEWER -> "Yangi versiya bor. Sozlamalar, kalitlar, tarjimalar va lug‘at saqlanib qoladi."
                        UpdateVerdict.CURRENT -> "Sizda eng oxirgi versiya o‘rnatilgan."
                        else -> "Versiyani solishtirib bo‘lmadi; kerak bo‘lsa yangilang."
                    },
                )
            }
        }
        if (opened) {
            Text(
                "Yuklab olish boshlandi (bildirishnomalar panelida ko\u2018rinadi). Tugagach, \u201CYuklab olindi\u201D " +
                    "bildirishnomasini bosing va \u201CO\u2018rnatish\u201Dni tanlang. Fayl \u201CDownload\u201D papkasida ham bo\u2018ladi.",
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
