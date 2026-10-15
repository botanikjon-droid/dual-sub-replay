package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * "Dasturni yangilash" for the Uzbek build. CI publishes each Uzbek build as the uz-latest
 * pre-release of the doctor's fork; its notes name the CI run ("(run 31)"), and the installed build's
 * version name ends with the same number ("-uz-build31"). The menu item compares the two, downloads the
 * APK inside the app and opens Android's installer (REQUEST_INSTALL_PACKAGES and a FileProvider are
 * declared only in the uz build's manifest). Only public release data is read; nothing is sent.
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

/** Where the downloaded APK waits for Android's installer; shared through the uz build's FileProvider. */
private fun updateFile(context: Context): File = File(File(context.cacheDir, UPDATE_DIRECTORY), "DualSub-Replay-uz.apk")

private const val UPDATE_DIRECTORY = "updates"
private const val DOWNLOAD_TIMEOUT_MS = 30_000
private const val BUFFER_BYTES = 64 * 1024
private const val MIN_APK_BYTES = 1_000_000L

/**
 * Downloads the APK inside the app (the browser's download screen could stop at 100 % without saving
 * the file). [onProgress] gets 0..1, or -1 when the size is unknown. The file is checked to be a
 * complete APK (a ZIP) before it is offered to the installer.
 */
private suspend fun downloadApk(
    context: Context,
    url: String,
    onProgress: (Float) -> Unit,
): File =
    withContext(Dispatchers.IO) {
        val target = updateFile(context)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "DualSubReplay-uz")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Yuklab bo‘lmadi (HTTP $code).")
            val total = connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Yuklash uzildi ($done / $total bayt). Qayta urinib ko‘ring.")
            if (!looksLikeApk(partial)) throw IOException("Yuklangan fayl APK emas. Qayta urinib ko‘ring.")
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Faylni saqlab bo‘lmadi.")
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

/** An APK is a ZIP file: at least a megabyte, starting with "PK". */
private fun looksLikeApk(file: File): Boolean {
    if (file.length() < MIN_APK_BYTES) return false
    val head = ByteArray(2)
    file.inputStream().use { if (it.read(head) != 2) return false }
    return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
}

/** Android 8+ asks once whether this app may install updates; until then its settings page is opened. */
private fun canInstallUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

private fun openInstallPermission(context: Context) {
    val intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** Hands the downloaded APK to Android's installer; the doctor confirms "O‘rnatish" there. */
private fun startInstaller(
    context: Context,
    apk: File,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

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

/** What the update dialog is doing. */
private sealed interface UpdateStep {
    data object Idle : UpdateStep

    data class Downloading(
        val progress: Float,
    ) : UpdateStep

    data class Ready(
        val apk: File,
    ) : UpdateStep
}

/** Checks uz-latest, downloads the APK inside the app and opens Android's installer. */
@Composable
@Suppress("LongMethod")
internal fun AppUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { installedUzBuild(BuildConfig.VERSION_NAME) }
    var release by remember { mutableStateOf<UzRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<UpdateStep>(UpdateStep.Idle) }
    LaunchedEffect(Unit) {
        try {
            release = fetchUzRelease()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: IOException) {
            error = failure.message ?: "Internetga ulanib bo‘lmadi."
        }
    }

    fun install(apk: File) {
        if (canInstallUpdates(context)) {
            startInstaller(context, apk)
        } else {
            error =
                "Telefon ruxsat so‘raydi: \u201CShu manbadan ruxsat berish\u201Dni yoqing, orqaga qayting va \u201CO‘rnatish\u201Dni bosing."
            openInstallPermission(context)
        }
    }

    fun download() {
        error = null
        step = UpdateStep.Downloading(0f)
        scope.launch {
            step =
                try {
                    val apk =
                        downloadApk(context, release?.apkUrl ?: UZ_APK_URL) { progress ->
                            step = UpdateStep.Downloading(progress)
                        }
                    install(apk)
                    UpdateStep.Ready(apk)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: IOException) {
                    error = failure.message ?: "Yuklab bo‘lmadi."
                    UpdateStep.Idle
                }
        }
    }

    val verdict = release?.let { updateVerdict(installed, it.build) }
    AlertDialog(
        onDismissRequest = { if (step !is UpdateStep.Downloading) onDismiss() },
        modifier = Modifier.testTag("app_update_dialog"),
        title = { Text("Dasturni yangilash") },
        text = { UpdateDialogBody(release, error, verdict, step) },
        confirmButton = {
            val ready = step as? UpdateStep.Ready
            TextButton(
                enabled = (release != null || error != null) && step !is UpdateStep.Downloading,
                modifier = Modifier.testTag("download_update"),
                onClick = { if (ready != null) install(ready.apk) else download() },
            ) { Text(if (ready != null) "O‘rnatish" else "Yuklab olish va o‘rnatish") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = step !is UpdateStep.Downloading,
                    onClick = { if (!openInBrowser(context, release?.apkUrl ?: UZ_APK_URL)) error = "Brauzer topilmadi." },
                ) { Text("Brauzerda") }
                TextButton(enabled = step !is UpdateStep.Downloading, onClick = onDismiss) { Text("Yopish") }
            }
        },
    )
}

/** The dialog's text: versions, verdict, download progress and the last error. */
@Composable
private fun UpdateDialogBody(
    release: UzRelease?,
    error: String?,
    verdict: UpdateVerdict?,
    step: UpdateStep,
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
                val latest = release
                Text("Eng oxirgi versiya: build ${latest.build ?: "?"} (${latest.publishedAt} UTC)")
                Text(
                    when (verdict) {
                        UpdateVerdict.NEWER -> "Yangi versiya bor. Sozlamalar, kalitlar va lug‘at saqlanib qoladi."
                        UpdateVerdict.CURRENT -> "Sizda eng oxirgi versiya o‘rnatilgan."
                        else -> "Versiyani solishtirib bo‘lmadi; kerak bo‘lsa yangilang."
                    },
                )
            }
        }
        when (val current = step) {
            is UpdateStep.Downloading ->
                if (current.progress >= 0f) {
                    LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Yuklanmoqda: ${(current.progress * PERCENT).toInt()}%")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            is UpdateStep.Ready -> Text("Yuklab olindi. Ochilgan oynada \u201CO‘rnatish\u201Dni bosing.")
            UpdateStep.Idle -> Unit
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private const val PERCENT = 100
