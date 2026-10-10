package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.UziGlossaryStore
import com.kienhoang.dualsubreplay.data.glossaryApplies
import com.kienhoang.dualsubreplay.data.glossaryGuidedSource
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The "uz" build's translator. Neither ML Kit nor Bergamot can translate Uzbek, so this
 * variant translates online: Gemini when the user chose it and entered their own key (falling
 * back to the others on any failure), else Google Cloud Translation when a key was supplied at
 * build time, otherwise the keyless gtx fallback. It keeps the exact public API of the other
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
    private val gemini = GeminiBatchTranslator { DubbingSettings.geminiApiKey.value }
    private val geminiLock = Mutex()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var prefetching = false

    @Volatile private var geminiPausedUntil = 0L
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
    ): String {
        // With the glossary switch on, Google gets the approved Uzbek terms; the cache key is that
        // guided text, so plain and guided translations never mix.
        suspend fun google(): String {
            val source = guidedSource(languages, text)
            return cached(languages, source) ?: translateOnline(languages, source)
        }
        if (!usesGemini(languages)) return google()
        return withFallback(
            primary = { translateWithGemini(languages, text) },
            onFailure = { error ->
                // No key, no quota or no network: Google Translate takes over, and Gemini rests a minute
                // so every sentence does not wait for the same failure.
                geminiPausedUntil = System.currentTimeMillis() + GEMINI_PAUSE_AFTER_FAILURE_MS
                DubbingSettings.publishTranslatorStatus("Gemini ishlamadi (${error.message}). Google Translate ishlatilmoqda.")
            },
            fallback = { google() },
        )
    }

    private fun usesGemini(languages: TranslationPair): Boolean =
        geminiEnabled(
            DubbingSettings.translationProvider.value,
            DubbingSettings.geminiApiKey.value,
            languages.source,
            languages.target,
            geminiPausedUntil,
            System.currentTimeMillis(),
        )

    /** Gemini results are cached apart from Google's, under the plain English sentence. */
    private fun geminiPair(languages: TranslationPair) = TranslationPair("${languages.source}$GEMINI_CACHE_SUFFIX", languages.target)

    private suspend fun translateWithGemini(
        languages: TranslationPair,
        text: String,
    ): String {
        val pair = geminiPair(languages)
        val hit =
            cached(pair, text) ?: geminiLock.withLock {
                // A prefetch may have translated it while this call waited.
                cached(pair, text) ?: run {
                    fetchGeminiBatch(pair, text)
                    cached(pair, text)
                }
            }
        prefetchGeminiAfter(pair, text)
        return hit ?: throw IOException("Gemini skipped the sentence.")
    }

    /** One request for [text] and the sentences after it that are not translated yet. */
    private suspend fun fetchGeminiBatch(
        pair: TranslationPair,
        text: String,
    ) {
        val batch = sentenceBatch(TranslationLookahead.sentences, text) { cache.get(pair.source, pair.target, it) != null }
        val glossary = glossaryForBatch(UziGlossaryStore.glossary.value, batch.sentences)
        val translations = withContext(Dispatchers.IO) { gemini.translate(batch, glossary) }
        batch.sentences.zip(translations).forEach { (sentence, translated) ->
            cache.put(pair.source, pair.target, sentence, translated)
            withContext(Dispatchers.IO) { diskCache?.put(pair.source, pair.target, sentence, translated) }
        }
        DubbingSettings.publishTranslatorStatus("Gemini: ${batch.sentences.size} ta gap tarjima qilindi.")
    }

    /** Fetches the next batch in the background before playback reaches it, so the video does not wait. */
    private fun prefetchGeminiAfter(
        pair: TranslationPair,
        text: String,
    ) {
        if (prefetching) return
        val next =
            sentencesAfter(TranslationLookahead.sentences, text).firstOrNull { cache.get(pair.source, pair.target, it) == null }
                ?: return
        prefetching = true
        prefetchScope.launch {
            try {
                geminiLock.withLock { if (cached(pair, next) == null) fetchGeminiBatch(pair, next) }
            } catch (ignored: IOException) {
                // The sentence is fetched again, or falls back to Google, when playback reaches it.
            } finally {
                prefetching = false
            }
        }
    }

    private fun guidedSource(
        languages: TranslationPair,
        text: String,
    ): String {
        if (!DubbingSettings.glossaryInTranslation.value) return text
        if (!glossaryApplies(languages.source, languages.target)) return text
        val glossary = UziGlossaryStore.glossary.value ?: return text
        return glossaryGuidedSource(glossary, text)
    }

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
        const val GEMINI_CACHE_SUFFIX = "+gemini-v1"
        const val GEMINI_PAUSE_AFTER_FAILURE_MS = 60_000L
        const val MAX_SKIPPED_IN_A_ROW = 3
    }
}
