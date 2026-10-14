package com.kienhoang.dualsubreplay.dubbing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Asks the real Microsoft Edge voice service for one short Uzbek sentence with each voice, through
 * the app's own client. It runs in CI with internet, so a broken voice shows up as a failed check
 * with the service's answer, instead of only on the doctor's phone.
 */
class EdgeVoiceLiveTest {
    @Test
    fun sardorAndMadinaReturnSpeech() =
        runBlocking {
            // Only in the voice-check workflow: ordinary CI must not depend on an outside service.
            assumeTrue(System.getenv("LIVE_VOICE_TEST") == "1")
            val directory = Files.createTempDirectory("dub").toFile()
            val voice = EdgeDubbingVoice(directory)
            for (name in listOf(EDGE_VOICE_SARDOR, EDGE_VOICE_MADINA)) {
                val clip = voice.synthesizePrepared(name, prepareUzbekSpeech("Jigar o‘lchamlari me’yorda, o‘t pufagi toza."))
                println("$name: ${clip.file.length()} bytes, ${clip.durationMs} ms")
                assertTrue("$name returned no audio", clip.file.length() > 2_000)
            }
        }
}
