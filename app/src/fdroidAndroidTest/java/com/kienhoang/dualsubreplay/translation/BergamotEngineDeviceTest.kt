package com.kienhoang.dualsubreplay.translation

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs the F-Droid build's native Bergamot engine on a device with real Mozilla
 * models. CI downloads the English→Vietnamese and Vietnamese→English models into
 * this test APK's assets (see tools/fetch_bergamot_test_models.py); nothing is
 * fetched during the test itself.
 */
class BergamotEngineDeviceTest {
    private val handles = mutableListOf<Long>()
    private lateinit var envi: String
    private lateinit var vien: String

    @Before fun copyModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val available = assets.list("bergamot-test-models").orEmpty().toSet()
        assumeTrue("Model assets were not downloaded for this build.", "en-vi" in available && "vi-en" in available)
        val root = File(instrumentation.targetContext.cacheDir, "bergamot-test-models")
        envi = copyPair(assets, root, "en-vi")
        vien = copyPair(assets, root, "vi-en")
    }

    @After fun releaseModels() {
        handles.forEach(BergamotNative::releaseModel)
    }

    @Test fun translatesEnglishToVietnamese() {
        val model = load(envi)
        val output = BergamotNative.translate(model, arrayOf("Tap any sentence to hear it again.", "Good morning."))
        assertEquals(2, output.size)
        assertTrue("Unexpected translation: ${output[0]}", output[0].contains("câu"))
        assertTrue("Unexpected translation: ${output[1]}", output[1].isNotBlank())
    }

    @Test fun pivotsThroughEnglish() {
        val first = load(vien)
        val second = load(envi)
        val output = BergamotNative.pivot(first, second, arrayOf("Tôi thích học tiếng Nhật qua YouTube."))
        assertTrue("Unexpected pivot: ${output[0]}", output[0].contains("tiếng Nhật"))
    }

    private fun load(config: String): Long = BergamotNative.loadModel(config).also(handles::add)

    private fun copyPair(
        assets: android.content.res.AssetManager,
        root: File,
        pair: String,
    ): String {
        val directory = File(root, pair).apply { mkdirs() }
        val names = assets.list("bergamot-test-models/$pair").orEmpty()
        names.forEach { name ->
            val target = File(directory, name)
            if (!target.isFile) {
                assets.open("bergamot-test-models/$pair/$name").use { input -> target.outputStream().use(input::copyTo) }
            }
        }

        fun file(prefix: String) = File(directory, names.first { it.startsWith(prefix) }).absolutePath
        val vocabulary = names.firstOrNull { it.startsWith("vocab") }
        return bergamotModelConfig(
            modelPath = file("model"),
            lexicalShortlistPath = file("lex"),
            sourceVocabularyPath = vocabulary?.let { File(directory, it).absolutePath } ?: file("srcvocab"),
            targetVocabularyPath = vocabulary?.let { File(directory, it).absolutePath } ?: file("trgvocab"),
        )
    }
}
