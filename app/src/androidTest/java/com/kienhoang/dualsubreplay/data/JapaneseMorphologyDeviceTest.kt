package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The APK leaves out Kuromoji's dictionary. This installs the exact Maven artifact the app
 * downloads (packaged in the test APK, so no network), checks it against the pinned checksum,
 * and proves Kuromoji loads it on ART.
 */
class JapaneseMorphologyDeviceTest {
    @Test
    fun downloadedDictionaryVerifiesAndGroupsWholeWords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "japanese-dictionary-test").apply { deleteRecursively() }
        val store = JapaneseDictionaryStore(directory, open = { instrumentation.context.assets.open("japanese-dictionary.jar") })

        assertTrue("The pinned size and SHA-256 must match the Maven artifact", store.install())

        val words = japaneseLearnerWords(store.loadTokenizer(), "毎日お母さんに手伝ってもらって、").map { it.text }
        assertEquals(listOf("毎日", "お母さん", "に", "手伝ってもらって", "、"), words)
        directory.deleteRecursively()
    }
}
