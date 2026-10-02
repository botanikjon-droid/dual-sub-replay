package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.BuildConfig

/**
 * Languages only the online "uz" build can translate; ML Kit and Bergamot cannot.
 * Kept out of TranslationLanguages.kt, whose list mirrors the website's language list.
 */
internal fun onlineOnlyLanguages(): List<TranslationLanguageOption> =
    if (BuildConfig.DISTRIBUTION == "uz") listOf(TranslationLanguageOption("uz", "Uzbek")) else emptyList()
