package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
 * version name ends with the same number ("-uz-build31"). The menu item compares the two and hands the
 * APK link to the browser, which downloads it and opens Android's installer (the app itself needs
 * no install permission). Only public release metadata is read; nothing is sent.
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

private fun openApkDownload(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: ActivityNotFoundException) {
        false
    }

/** Checks uz-latest and offers the download; the browser and Android's installer do the rest. */
@Composable
internal fun AppUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed = remember { installedUzBuild(BuildConfig.VERSION_NAME) }
    var release by remember { mutableStateOf<UzRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
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
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O‘rnatilgan versiya: ${BuildConfig.VERSION_NAME}")
                when {
                    error != null -> Text("Tekshirib bo‘lmadi: $error Baribir yuklab olishingiz mumkin.")
                    release == null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Yangi versiya tekshirilmoqda…")
                        }
                    else -> {
                        val latest = release!!
                        Text("Eng oxirgi versiya: build ${latest.build ?: "?"} (${latest.publishedAt} UTC)")
                        Text(
                            when (verdict) {
                                UpdateVerdict.NEWER -> "Yangi versiya bor. Yuklab oling va o‘rnating: sozlamalar va lug‘at saqlanib qoladi."
                                UpdateVerdict.CURRENT -> "Sizda eng oxirgi versiya o‘rnatilgan."
                                else -> "Versiyani solishtirib bo‘lmadi; kerak bo‘lsa yuklab oling."
                            },
                        )
                    }
                }
                Text(
                    "Yuklab olingach, faylni oching va “O‘rnatish” ni bosing. Telefon so‘rasa, brauzerga " +
                        "noma’lum manbalardan o‘rnatishga ruxsat bering.",
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = release != null || error != null,
                modifier = Modifier.testTag("download_update"),
                onClick = {
                    if (openApkDownload(context, release?.apkUrl ?: UZ_APK_URL)) {
                        onDismiss()
                    } else {
                        error = "Brauzer topilmadi."
                    }
                },
            ) { Text("Yuklab olish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Yopish") } },
    )
}
