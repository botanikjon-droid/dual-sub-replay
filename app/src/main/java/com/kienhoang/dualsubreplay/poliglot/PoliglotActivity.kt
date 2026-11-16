package com.kienhoang.dualsubreplay.poliglot

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.kienhoang.dualsubreplay.data.writeFileAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * "Poliglot" screen: the course page from assets/poliglot/, served from the app's own https origin by
 * WebViewAssetLoader (no file access), with a bridge named "Android" for the phone's voice (TTS), speech
 * recognition, the doctor's own recordings and the daily reminder. A screen of its own, so SHOKALON's lesson
 * page (the single YouTube WebView) is left as it is and only paused while Poliglot is open.
 */

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$ASSET_HOST/poliglot/index.html"
private const val INK = 0xFF14213D.toInt()
private const val TAG = "Poliglot"

class PoliglotActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null

    @Volatile private var ttsReady = false
    private val recordingLock = Any()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (result.resultCode == RESULT_OK) toPage("speech", heard.orEmpty()) else toPage("speechError", "cancel")
        }
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toPage("mic", if (granted) "granted" else "denied")
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toPage("notif", if (granted) "granted" else "denied")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader =
            WebViewAssetLoader
                .Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/poliglot/", WebViewAssetLoader.AssetsPathHandler(this))
                .build()
        web =
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = PoliglotClient(loader)
                addJavascriptInterface(Bridge(), "Android")
            }
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Android 15+ draws every screen edge to edge: keep the page clear of the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        PoliglotAlarm.ensureChannel(this)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    web.evaluateJavascript("window.onBack?window.onBack():false") { handled -> if (handled != "true") finish() }
                }
            },
        )
        web.loadUrl(START_URL)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        synchronized(recordingLock) {
            recorder?.let { runCatching { it.stop() } }
            recorder?.release()
            recorder = null
        }
        stopPlayer()
        tts?.shutdown()
        tts = null
        web.removeJavascriptInterface("Android")
        web.destroy()
        super.onDestroy()
    }

    /** Tells the page about something that happened outside it: `window.onNative(type, arg)`. */
    private fun toPage(
        type: String,
        arg: String,
    ) {
        val code = "window.onNative&&window.onNative(${JSONObject.quote(type)},${JSONObject.quote(arg)})"
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun recordingsDir(): File = File(filesDir, POLIGLOT_RECORDINGS_DIR).apply { mkdirs() }

    private fun stopPlayer() {
        player?.let { current ->
            runCatching { current.stop() }
            current.release()
        }
        player = null
    }

    private fun hasPermission(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Only the course's own pages load here; nothing leaves for the web. */
    private class PoliglotClient(
        private val loader: WebViewAssetLoader,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = request.url.host != ASSET_HOST
    }

    /** What the page may ask of the phone. Called on a background thread of the WebView. */
    inner class Bridge {
        @JavascriptInterface
        fun speak(
            text: String,
            lang: String?,
            rate: Float,
        ) {
            val engine = tts
            if (!ttsReady || engine == null) {
                toPage("tts", "notready")
                return
            }
            val locale = if (lang?.startsWith("ar") == true) Locale.forLanguageTag("ar") else Locale.US
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                toPage("tts", "missing:${locale.language}")
                return
            }
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poliglot")
        }

        @JavascriptInterface
        fun listen(lang: String?) {
            val language = lang?.takeIf(String::isNotBlank) ?: "en-US"
            runOnUiThread {
                val intent =
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Gapiring...")
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                try {
                    speech.launch(intent)
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "No speech recognizer", error)
                    toPage("speechError", "none")
                }
            }
        }

        @JavascriptInterface
        fun startRecording(tag: String?): Boolean {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                runOnUiThread { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                return false
            }
            runOnUiThread { stopPlayer() }
            synchronized(recordingLock) {
                if (recorder != null) return true
                val file = File(recordingsDir(), poliglotRecordingName(System.currentTimeMillis(), tag))
                val next =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@PoliglotActivity) else legacyRecorder()
                return try {
                    next.setAudioSource(MediaRecorder.AudioSource.MIC)
                    next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    next.setAudioEncodingBitRate(96_000)
                    next.setAudioSamplingRate(44_100)
                    next.setOutputFile(file.absolutePath)
                    next.prepare()
                    next.start()
                    recorder = next
                    recordingFile = file
                    true
                } catch (error: IOException) {
                    Log.w(TAG, "Recording not started", error)
                    next.release()
                    false
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Recording not started", error)
                    next.release()
                    false
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun legacyRecorder() = MediaRecorder()

        @JavascriptInterface
        fun stopRecording(): String =
            synchronized(recordingLock) {
                val current = recorder ?: return ""
                val stopped = runCatching { current.stop() }.isSuccess
                current.release()
                recorder = null
                val file = recordingFile
                recordingFile = null
                when {
                    file == null -> ""
                    !stopped -> "".also { file.delete() }
                    file.exists() -> file.name
                    else -> ""
                }
            }

        @JavascriptInterface
        fun listRecordings(): String {
            val list = JSONArray()
            recordingsDir()
                .listFiles()
                .orEmpty()
                .filter { isPoliglotRecordingName(it.name) }
                .sortedByDescending(File::lastModified)
                .forEach { file ->
                    list.put(
                        JSONObject()
                            .put("name", file.name)
                            .put("time", file.lastModified())
                            .put("dur", durationOf(file)),
                    )
                }
            return list.toString()
        }

        private fun durationOf(file: File): Long {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (error: RuntimeException) {
                Log.w(TAG, "No duration for ${file.name}", error)
                0L
            } finally {
                runCatching { retriever.release() }
            }
        }

        @JavascriptInterface
        fun play(name: String?) {
            if (!isPoliglotRecordingName(name)) return
            val file = File(recordingsDir(), name.orEmpty())
            runOnUiThread {
                stopPlayer()
                if (!file.isFile) return@runOnUiThread
                val next = MediaPlayer()
                try {
                    next.setDataSource(file.absolutePath)
                    next.setOnCompletionListener {
                        stopPlayer()
                        toPage("playEnd", file.name)
                    }
                    next.prepare()
                    next.start()
                    player = next
                    toPage("playStart", file.name)
                } catch (error: IOException) {
                    Log.w(TAG, "Recording not played", error)
                    next.release()
                    toPage("playEnd", file.name)
                }
            }
        }

        @JavascriptInterface
        fun stopPlay() {
            runOnUiThread {
                stopPlayer()
                toPage("playEnd", "")
            }
        }

        @JavascriptInterface
        fun deleteRecording(name: String?): Boolean = isPoliglotRecordingName(name) && File(recordingsDir(), name.orEmpty()).delete()

        @JavascriptInterface
        fun share(text: String?) {
            val report = text.orEmpty()
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "Hisobotni yuborish"))
            }
        }

        @JavascriptInterface
        fun copy(text: String?) {
            val report = text.orEmpty()
            runOnUiThread {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Poliglot", report))
            }
        }

        @JavascriptInterface
        fun setReminder(
            hour: Int,
            minute: Int,
        ) {
            if (!isPoliglotClockTime(hour, minute)) return
            prefs()
                .edit()
                .putInt("hour", hour)
                .putInt("minute", minute)
                .apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                runOnUiThread { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            PoliglotAlarm.schedule(this@PoliglotActivity)
        }

        @JavascriptInterface
        fun cancelReminder() {
            prefs().edit().putInt("hour", -1).apply()
            PoliglotAlarm.schedule(this@PoliglotActivity)
        }

        @JavascriptInterface
        fun setStatus(status: String?) {
            prefs().edit().putString("status", status.orEmpty()).apply()
        }

        /** The course progress, kept in the app's own files so it outlives the page's browser storage. */
        @JavascriptInterface
        fun loadState(): String {
            val file = File(filesDir, POLIGLOT_STATE_FILE)
            return try {
                if (file.isFile) file.readText(Charsets.UTF_8) else ""
            } catch (error: IOException) {
                Log.w(TAG, "Progress not read", error)
                ""
            }
        }

        @JavascriptInterface
        fun saveState(json: String?) {
            if (json.isNullOrBlank()) return
            try {
                writeFileAtomically(File(filesDir, POLIGLOT_STATE_FILE), json)
            } catch (error: IOException) {
                Log.w(TAG, "Progress not saved", error)
            }
        }

        private fun prefs() = getSharedPreferences(POLIGLOT_PREFS, Context.MODE_PRIVATE)
    }

    companion object {
        /** Opens Poliglot from SHOKALON's home screen. */
        fun open(context: Context) {
            context.startActivity(Intent(context, PoliglotActivity::class.java))
        }
    }
}
