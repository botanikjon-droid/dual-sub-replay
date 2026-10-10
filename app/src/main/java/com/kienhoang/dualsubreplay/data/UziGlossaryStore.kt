package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The UZI glossary, read once from the app's assets. Null until loaded or if the asset is broken. */
internal object UziGlossaryStore {
    private val _glossary = MutableStateFlow<UziGlossary?>(null)
    val glossary: StateFlow<UziGlossary?> = _glossary

    fun load(
        context: Context,
        scope: CoroutineScope,
    ) {
        if (_glossary.value != null) return
        val assets = context.applicationContext.assets
        scope.launch(Dispatchers.IO) {
            try {
                val text = assets.open(UZI_GLOSSARY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseUziGlossary(text)
                if (parsed.errors.isNotEmpty()) Log.w(TAG, "Glossary lines skipped: ${parsed.errors}")
                _glossary.value = UziGlossary(parsed.entries)
            } catch (error: java.io.IOException) {
                // The app works without the glossary; only the term hints are missing.
                Log.w(TAG, "Glossary not loaded", error)
            }
        }
    }

    private const val TAG = "UziGlossary"
}
