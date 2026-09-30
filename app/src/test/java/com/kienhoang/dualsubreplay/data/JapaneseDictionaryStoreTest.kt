package com.kienhoang.dualsubreplay.data

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class JapaneseDictionaryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The Kuromoji IPADIC jar on the test classpath stands in for the downloaded file. */
    private val dictionaryJar =
        File(
            Tokenizer::class.java.protectionDomain.codeSource.location
                .toURI(),
        )
    private val jarSha256 =
        MessageDigest.getInstance("SHA-256").digest(dictionaryJar.readBytes()).joinToString("") { "%02x".format(it) }

    private fun store(
        directory: File = folder.newFolder("dictionary"),
        open: (String) -> java.io.InputStream,
    ) = JapaneseDictionaryStore(directory, dictionaryJar.length(), jarSha256, open)

    @Test
    fun installsTheVerifiedFileAndLoadsKuromojiFromIt() {
        val requested = mutableListOf<String>()
        val store =
            store { url ->
                requested += url
                dictionaryJar.inputStream()
            }

        assertTrue(store.install())
        assertTrue(store.isInstalled())
        assertEquals(listOf(JapaneseDictionaryRelease.URLS.first()), requested)

        val words = japaneseLearnerWords(store.loadTokenizer(), "毎日お母さんに手伝ってもらって").map { it.text }
        assertEquals(listOf("毎日", "お母さん", "に", "手伝ってもらって"), words)
    }

    @Test
    fun aFailingHostFallsBackToTheMirror() {
        val store =
            store { url ->
                if (url == JapaneseDictionaryRelease.URLS.first()) throw IOException("rate limited")
                dictionaryJar.inputStream()
            }

        assertTrue(store.install())
    }

    @Test
    fun damagedOrOversizedDownloadsAreRejectedAndLeaveNothingBehind() {
        val directory = folder.newFolder("dictionary")
        val bytes = dictionaryJar.readBytes()
        val damaged = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val oversized = bytes + ByteArray(10)

        assertFalse(store(directory) { ByteArrayInputStream(damaged) }.install())
        assertFalse(store(directory) { ByteArrayInputStream(oversized) }.install())
        assertFalse(store(directory) { ByteArrayInputStream(bytes.copyOf(bytes.size - 1)) }.install())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anInstalledDictionaryIsNotDownloadedAgain() {
        val directory = folder.newFolder("dictionary")
        assertTrue(store(directory) { dictionaryJar.inputStream() }.install())

        assertTrue(store(directory) { error("must not download again") }.install())
    }

    @Test
    fun releasePinMatchesTheMavenArtifactName() {
        assertTrue(
            JapaneseDictionaryRelease.URLS.all { it.startsWith("https://") && it.endsWith("/" + JapaneseDictionaryRelease.FILE_NAME) },
        )
        assertEquals(64, JapaneseDictionaryRelease.SHA256.length)
    }
}
