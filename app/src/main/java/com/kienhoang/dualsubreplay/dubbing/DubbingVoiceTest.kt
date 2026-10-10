package com.kienhoang.dualsubreplay.dubbing

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The same sentence for every voice, so the doctor can compare them by ear. */
internal const val UZI_VOICE_TEST_TEXT =
    "O‘zbekistonlik UZI shifokori jigar, portal vena va umumiy o‘t yo‘lini tekshiradi. " +
        "Gipoexogen o‘choq, echogenlik va siljish to‘lqini elastografiyasi natijalarini baholaydi. " +
        "O‘ng bo‘lakdagi to‘qimaning qattiqligi kilopaskalda ifodalanadi."

/**
 * Speaks a test sentence with any listed voice. The voice build sets [synthesize]; the other
 * builds leave it null and show no test.
 */
internal object DubbingVoiceSample {
    @Volatile
    var synthesize: (suspend (voiceId: String, prepared: PreparedSpeech) -> DubClip)? = null

    @Volatile
    var acronyms: () -> Map<String, String> = { emptyMap() }
}

/** "Test the voice": plays the test sentence and shows what the voice was given and which rules ran. */
@Composable
internal fun VoiceTestControls() {
    val synthesize = DubbingVoiceSample.synthesize ?: return
    val options by DubbingSettings.speechOptions.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player[0]?.release() } }
    Text("Ovoz sinovi", modifier = Modifier.padding(top = 10.dp))
    Text(
        "Har bir ovoz bir xil UZI matnini o‘qiydi. Talaffuzni quloq bilan tekshiring.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DubbingSettings.voices.forEach { voice ->
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val prepared = prepareUzbekSpeech(UZI_VOICE_TEST_TEXT, options, DubbingVoiceSample.acronyms())
                        status = "${voice.label}: tayyorlanmoqda…"
                        status =
                            try {
                                val clip = synthesize(voice.id, prepared)
                                player[0]?.release()
                                player[0] =
                                    MediaPlayer().apply {
                                        setDataSource(clip.file.path)
                                        setOnCompletionListener { it.release() }
                                        prepare()
                                        start()
                                    }
                                "${voice.label}: ${clip.durationMs / MS_IN_SECOND} s. Qoidalar: ${prepared.ruleSummary()}.\n" +
                                    "Ovozga berilgan matn: ${prepared.tts}"
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                "${voice.label}: ovoz olinmadi (${error.message}). Internetni tekshiring."
                            }
                    }
                },
                modifier = Modifier.testTag("dubbing_voice_test_${voice.id}"),
            ) { Text(voice.label.substringBefore(" (")) }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dubbing_voice_test_status")) }
    SpeechRuleSwitch(
        "Birliklarni so‘z bilan o‘qish (sinov)",
        "12 kPa → 12 kilopaskal, 4 mm → 4 millimetr",
        options.readUnits,
        "dubbing_speech_units",
    ) { DubbingSettings.setSpeechOptions(options.copy(readUnits = it)) }
    SpeechRuleSwitch(
        "Qisqartmalarni to‘liq o‘qish (sinov)",
        "RI → rezistivlik indeksi, TGC → … (UZI lug‘atidan)",
        options.expandAcronyms,
        "dubbing_speech_acronyms",
    ) { DubbingSettings.setSpeechOptions(options.copy(expandAcronyms = it)) }
}

@Composable
internal fun SpeechRuleSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

private const val MS_IN_SECOND = 1_000L
