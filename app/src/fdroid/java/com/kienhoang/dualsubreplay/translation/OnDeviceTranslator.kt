package com.kienhoang.dualsubreplay.translation

import java.io.File

/**
 * F-Droid build of the translator. It must not link Google ML Kit, which is
 * proprietary, so it mirrors the full build's API without a translation engine.
 * Same-language text passes through so original captions still work; any real
 * translation fails with a message the UI already shows as a retryable error.
 */
@Suppress("UNUSED_PARAMETER")
class OnDeviceTranslator(
    cacheDirectory: File? = null,
) {
    suspend fun prepare(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
    ) {
        if (!isSameLanguage(sourceLanguageCode, targetLanguageCode)) throw unavailable()
    }

    suspend fun translateSingle(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String = withSession(sourceLanguageCode, targetLanguageCode) { translate -> translate(text) }

    internal suspend fun <T> withSession(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        block: suspend (suspend (String) -> String) -> T,
    ): T {
        val sameLanguage = isSameLanguage(sourceLanguageCode, targetLanguageCode)
        return block { text ->
            if (text.isBlank() || sameLanguage) text else throw unavailable()
        }
    }

    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) = withSession(sourceLanguageCode, targetLanguageCode, onDownloadingChange) { translate ->
        texts.forEachIndexed { index, text -> onTranslation(index, translate(text)) }
    }

    private fun isSameLanguage(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): Boolean = TranslationLanguages.normalize(sourceLanguageCode) == TranslationLanguages.normalize(targetLanguageCode)

    private fun unavailable() =
        UnsupportedOperationException(
            "Translation is not available in this F-Droid build yet. Original captions still work.",
        )
}
