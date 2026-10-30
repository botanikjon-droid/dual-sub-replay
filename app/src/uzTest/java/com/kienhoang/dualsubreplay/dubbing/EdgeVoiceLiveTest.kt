package com.kienhoang.dualsubreplay.dubbing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * Asks the real Microsoft voice service for one short Uzbek sentence through the app's own client: each
 * address alone (so the report says which ones still answer), then both voices the way the app does.
 * It runs only in the uz-voice-check workflow (daily and on changes), so a moved or blocked address
 * shows up as a failed check before the doctor notices a silent voice.
 */
class EdgeVoiceLiveTest {
    private val sentence = prepareUzbekSpeech("Jigar o‘lchamlari me’yorda, o‘t pufagi toza.")

    @Test
    fun theVoicesAnswerAtLeastAtOneAddress() =
        runBlocking {
            // Only in the voice-check workflow: ordinary CI must not depend on an outside service.
            assumeTrue(System.getenv("LIVE_VOICE_TEST") == "1")
            val working =
                EDGE_ENDPOINTS.filter { endpoint ->
                    val voice = EdgeDubbingVoice(Files.createTempDirectory("dub").toFile(), endpoints = listOf(endpoint))
                    try {
                        val clip = voice.synthesizePrepared(EDGE_VOICE_SARDOR, sentence)
                        println("address ${endpoint.name}: OK, ${clip.file.length()} bytes")
                        clip.file.length() > MIN_AUDIO_BYTES
                    } catch (error: IOException) {
                        println("address ${endpoint.name}: FAILED ${error.message}")
                        false
                    }
                }
            assertTrue("No voice address answers: the app is silent", working.isNotEmpty())

            val app = EdgeDubbingVoice(Files.createTempDirectory("dub").toFile())
            for (name in listOf(EDGE_VOICE_SARDOR, EDGE_VOICE_MADINA)) {
                val clip = app.synthesizePrepared(name, sentence)
                println("$name: ${clip.file.length()} bytes, ${clip.durationMs} ms")
                assertTrue("$name returned no audio", clip.file.length() > MIN_AUDIO_BYTES)
            }
        }

    private companion object {
        const val MIN_AUDIO_BYTES = 2_000L
    }
}
