package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The "uz" build's translator. Neither ML Kit nor Bergamot can translate Uzbek, so this
 * variant translates online: Google Cloud Translation when a key was supplied at build
 * time, otherwise the keyless gtx fallback. It keeps the exact public API of the other
 * variants, so AppViewModel and the UI do not know which provider answered.
 */
class OnDeviceTranslator(
    cacheDirectory: File? = null,
    // Used by the F-Droid build's downloadable models; online translation needs none.
    @Suppress("UNUSED_PARAMETER") modelDirectory: File? = null,
) {
    // A separate folder, so cached online results never mix with another engine's.
    private val diskCache =
        cacheDirectory?.let { TranslationDiskCache(File(it.parentFile, "${it.name}-$CACHE_VERSION")) }
    private val cache = TranslationCache()
    private val online =
        OnlineTranslationChain(
            buildList {
                BuildConfig.GOOGLE_TRANSLATE_API_KEY.takeIf(String::isNotBlank)?.let {
                    add(GoogleCloudTranslationProvider(it))
                }
                add(GtxTranslationProvider())
            },
        )

    /** Online translation has no model to download; this only validates the language pair. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun prepare(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
    ) {
        resolveLanguages(sourceLanguageCode, targetLanguageCode)
    }

    suspend fun translateSingle(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String = withSession(sourceLanguageCode, targetLanguageCode) { translate -> translate(text) }

    /** Same contract as the other variants; there is no client to open or close. */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun <T> withSession(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        block: suspend (suspend (String) -> String) -> T,
    ): T {
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        var lastSentence: String? = null
        var failuresInARow = 0
        return block { text ->
            when {
                text.isBlank() || languages.source == languages.target -> text
                // Playback translates a sentence, then each of its row prefixes only to place the
                // row breaks. Offline that is free; online it costs one request per row. A blank
                // prefix makes the caption code split the sentence proportionally instead.
                isPrefixOf(text, lastSentence) -> ""
                else -> {
                    lastSentence = text
                    // Playback stops translating for good after one error, so a brief network
                    // or rate-limit failure leaves one sentence blank instead; only repeated
                    // failures (really offline) are reported.
                    try {
                        translateText(languages, text).also { failuresInARow = 0 }
                    } catch (error: IOException) {
                        if (++failuresInARow >= MAX_SKIPPED_IN_A_ROW) throw error
                        ""
                    }
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) {
        // Every text here is a full caption, never a row prefix, so none is skipped.
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        texts.forEachIndexed { index, text ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val translated =
                if (text.isBlank() || languages.source == languages.target) text else translateText(languages, text)
            onTranslation(index, translated)
        }
    }

    private suspend fun translateText(
        languages: TranslationPair,
        text: String,
    ): String = cached(languages, text) ?: translateOnline(languages, text)

    private fun isPrefixOf(
        text: String,
        sentence: String?,
    ): Boolean {
        val prefix = text.trim()
        val whole = sentence?.trim() ?: return false
        return prefix.length < whole.length && whole.startsWith(prefix)
    }

    private suspend fun cached(
        languages: TranslationPair,
        text: String,
    ): String? {
        val hit =
            cache.get(languages.source, languages.target, text)
                ?: withContext(Dispatchers.IO) { diskCache?.get(languages.source, languages.target, text) }
        if (hit != null) cache.put(languages.source, languages.target, text, hit)
        return hit
    }

    private suspend fun translateOnline(
        languages: TranslationPair,
        text: String,
    ): String {
        val translated = withContext(Dispatchers.IO) { online.translate(languages.source, languages.target, text) }
        cache.put(languages.source, languages.target, text, translated)
        withContext(Dispatchers.IO) { diskCache?.put(languages.source, languages.target, text, translated) }
        return translated
    }

    private fun resolveLanguages(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): TranslationPair {
        val source = TranslationLanguages.normalize(sourceLanguageCode)
        val target = TranslationLanguages.normalize(targetLanguageCode)
        require(TranslationLanguages.isSupported(source)) {
            "${TranslationLanguages.displayName(sourceLanguageCode)} translation is not supported."
        }
        require(TranslationLanguages.isSupported(target)) {
            "${TranslationLanguages.displayName(targetLanguageCode)} translation is not supported."
        }
        return TranslationPair(source, target)
    }

    private data class TranslationPair(
        val source: String,
        val target: String,
    )

    private companion object {
        // Bump when the provider or its output changes, to drop stale cached translations.
        const val CACHE_VERSION = "uz-online-v1"
        const val MAX_SKIPPED_IN_A_ROW = 3
    }
}
