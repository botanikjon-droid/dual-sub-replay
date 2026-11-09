package com.kienhoang.dualsubreplay.dubbing

/*
 * The official Microsoft Azure AI Speech service has the same Uzbek neural voices as the free Edge
 * "Read aloud" endpoint (uz-UZ-SardorNeural, uz-UZ-MadinaNeural). The free endpoint is undocumented
 * and Microsoft can block third-party apps at any time; with the doctor's own Azure Speech key
 * (free tier: 0.5 million characters a month) the voices keep working. Pure helpers; the HTTP call
 * lives in the "uz" build next to the Edge client.
 */

/** "West Europe", "westeurope " and "WESTEUROPE" all mean the Azure region id "westeurope". */
internal fun normalizeAzureRegion(region: String): String = region.lowercase().filter { it.isLetterOrDigit() }

internal fun azureTtsUrl(region: String): String = "https://${normalizeAzureRegion(region)}.tts.speech.microsoft.com/cognitiveservices/v1"

/** Same output as the Edge client, so clip lengths and the cache work the same way. */
internal const val AZURE_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

internal fun azureSsml(
    voice: String,
    text: String,
): String {
    val cleaned = text.map { if (it.code in 0..8 || it.code in 11..12 || it.code in 14..31) ' ' else it }.joinToString("")
    val escaped = cleaned.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='uz-UZ'>" +
        "<voice name='$voice'>$escaped</voice></speak>"
}

/** A short Uzbek reason for a failed Azure request. */
internal fun azureSpeechError(code: Int): String =
    when (code) {
        401, 403 -> "Azure Speech kaliti yoki hududi noto‘g‘ri."
        429 -> "Azure Speech oylik yoki daqiqalik limiti tugadi."
        else -> "Azure Speech xatosi (HTTP $code)."
    }

/** What the voice card says when the free Edge voice fails, so the doctor knows the fix. */
internal fun edgeVoiceFailureMessage(detail: String?): String =
    "Microsoft bepul ovozi (Sardor/Madina) javob bermadi" + (detail?.let { " ($it)" } ?: "") +
        ". Ovoz ishlashi uchun Azure Speech kalitini kiriting."
