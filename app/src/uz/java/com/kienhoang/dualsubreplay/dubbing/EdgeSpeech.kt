package com.kienhoang.dualsubreplay.dubbing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Uzbek speech from Microsoft Edge's online "Read aloud" voices (uz-UZ Sardor / Madina).
 * Undocumented, like gtx: it follows the open-source edge-tts client and lives only in the
 * "uz" build, so it can be replaced without touching the rest of the app.
 */

internal fun createDubbingVoice(context: Context): DubbingVoice? {
    DubbingSettings.load(
        context,
        listOf(
            DubbingVoiceOption(EDGE_VOICE_SARDOR, "Sardor (erkak)"),
            DubbingVoiceOption(EDGE_VOICE_MADINA, "Madina (ayol)"),
        ),
    )
    return EdgeDubbingVoice(File(context.cacheDir, "dub-uz-v1"))
}

internal const val EDGE_TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
internal const val EDGE_CHROMIUM_VERSION = "143.0.3650.75"
internal const val EDGE_WSS_URL =
    "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$EDGE_TRUSTED_CLIENT_TOKEN"
internal const val EDGE_VOICE_SARDOR = "uz-UZ-SardorNeural"
internal const val EDGE_VOICE_MADINA = "uz-UZ-MadinaNeural"

/** edge-tts output format: 48 kbit/s constant bitrate, so the byte count gives the length. */
private const val MP3_BYTES_PER_SECOND = 48_000 / 8
private const val WINDOWS_EPOCH_OFFSET_S = 11_644_473_600L
private const val TOKEN_WINDOW_S = 300L
private const val HUNDRED_NS_PER_SECOND = 10_000_000L

/** The Sec-MS-GEC token: SHA-256 of the 5-minute Windows file time and the client token. */
internal fun edgeSecMsGec(unixSeconds: Long): String {
    var ticks = unixSeconds + WINDOWS_EPOCH_OFFSET_S
    ticks -= ticks % TOKEN_WINDOW_S
    val digest = MessageDigest.getInstance("SHA-256").digest("${ticks * HUNDRED_NS_PER_SECOND}$EDGE_TRUSTED_CLIENT_TOKEN".toByteArray())
    return digest.joinToString("") { "%02X".format(it) }
}

internal fun edgeVoiceName(shortName: String): String {
    val (language, region, name) = shortName.split("-", limit = 3)
    return "Microsoft Server Speech Text to Speech Voice ($language-$region, $name)"
}

internal fun edgeSsml(
    voice: String,
    text: String,
): String {
    val cleaned = text.map { if (it.code in 0..8 || it.code in 11..12 || it.code in 14..31) ' ' else it }.joinToString("")
    val escaped = cleaned.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
        "<voice name='${edgeVoiceName(voice)}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
        "$escaped</prosody></voice></speak>"
}

private fun edgeTimestamp(): String =
    SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

internal fun edgeConfigMessage(): String =
    "X-Timestamp:${edgeTimestamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\"," +
        "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

internal fun edgeSsmlMessage(ssml: String): String =
    "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\n" +
        "X-Timestamp:${edgeTimestamp()}Z\r\nPath:ssml\r\n\r\n$ssml"

/** The audio inside a binary frame: [2-byte header length][headers][audio], or null if not audio. */
internal fun edgeAudioPayload(frame: ByteArray): ByteArray? {
    if (frame.size < 2) return null
    val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
    if (2 + headerLength > frame.size) return null
    val headers = String(frame, 2, headerLength, Charsets.UTF_8)
    if (!headers.split("\r\n").contains("Path:audio")) return null
    return frame.copyOfRange(2 + headerLength, frame.size)
}

/** Fetches Uzbek speech and caches it as mp3 files named by a hash of voice and text. */
internal class EdgeDubbingVoice(
    private val directory: File,
    private val voiceOf: () -> String = { DubbingSettings.voiceId.value.ifEmpty { EDGE_VOICE_SARDOR } },
    private val url: String = EDGE_WSS_URL,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build(),
) : DubbingVoice {
    override val language = "uz"

    /** Server clock minus device clock, learned from a rejected token, as edge-tts does. */
    private var clockSkewSeconds = 0L
    private var writesSinceTrim = 0

    override suspend fun synthesize(text: String): DubClip =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val voice = voiceOf()
            // The voice only says oʻ and gʻ correctly with their official apostrophe.
            val spoken = uzbekSpeechText(text)
            val file = File(directory, "${sha1("$voice\n$spoken")}.mp3")
            if (file.length() == 0L) {
                val audio =
                    try {
                        request(voice, spoken)
                    } catch (error: SkewedClock) {
                        clockSkewSeconds = error.serverSeconds - System.currentTimeMillis() / 1000
                        request(voice, spoken)
                    }
                val partial = File(directory, "${file.name}.part")
                partial.writeBytes(audio)
                if (!partial.renameTo(file)) throw IOException("Could not save the speech clip.")
                if (++writesSinceTrim >= TRIM_EVERY_WRITES) {
                    writesSinceTrim = 0
                    trimEdgeCache(directory, CACHE_MAX_BYTES, CACHE_TRIM_TO_BYTES)
                }
            }
            DubClip(file, file.length() * 1000 / MP3_BYTES_PER_SECOND)
        }

    private suspend fun request(
        voice: String,
        text: String,
    ): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val audio = ByteArrayOutputStream()
            val now = System.currentTimeMillis() / 1000 + clockSkewSeconds
            val request =
                Request
                    .Builder()
                    .url(
                        "$url&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}" +
                            "&Sec-MS-GEC=${edgeSecMsGec(now)}&Sec-MS-GEC-Version=1-$EDGE_CHROMIUM_VERSION",
                    ).header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header("User-Agent", EDGE_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
                    .build()
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send(edgeConfigMessage())
                            webSocket.send(edgeSsmlMessage(edgeSsml(voice, text)))
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            edgeAudioPayload(bytes.toByteArray())?.let(audio::write)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (!text.contains("Path:turn.end")) return
                            webSocket.close(NORMAL_CLOSURE, null)
                            if (!continuation.isActive) return
                            if (audio.size() == 0) {
                                continuation.resumeWithException(IOException("The voice service returned no audio."))
                            } else {
                                continuation.resume(audio.toByteArray())
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (!continuation.isActive) return
                            val serverDate = response?.headers?.getDate("Date")
                            val error =
                                if (response?.code == HTTP_FORBIDDEN && serverDate != null) {
                                    SkewedClock(serverDate.time / 1000)
                                } else {
                                    IOException("The voice service is unavailable: ${response?.code ?: t.message}", t)
                                }
                            continuation.resumeWithException(error)
                        }
                    },
                )
            continuation.invokeOnCancellation { socket.cancel() }
        }

    private class SkewedClock(
        val serverSeconds: Long,
    ) : IOException("Device clock differs from the voice service.")

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val TRIM_EVERY_WRITES = 25
        const val CACHE_MAX_BYTES = 50L * 1024 * 1024
        const val CACHE_TRIM_TO_BYTES = 40L * 1024 * 1024
        const val HTTP_FORBIDDEN = 403
        val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${EDGE_CHROMIUM_VERSION.substringBefore('.')}.0.0.0 Safari/537.36 " +
                "Edg/${EDGE_CHROMIUM_VERSION.substringBefore('.')}.0.0.0"
    }
}

private fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Deletes the oldest clips once the folder passes [maxBytes], down to [targetBytes]. */
internal fun trimEdgeCache(
    directory: File,
    maxBytes: Long,
    targetBytes: Long,
) {
    val files = directory.listFiles()?.filter { it.isFile } ?: return
    var total = files.sumOf(File::length)
    if (total <= maxBytes) return
    for (file in files.sortedBy(File::lastModified)) {
        if (total <= targetBytes) break
        val size = file.length()
        if (file.delete()) total -= size
    }
}
