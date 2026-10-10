package com.kienhoang.dualsubreplay.dubbing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The "Uzbek voice" card at the top of the settings; shown only in a build with a voice. */
@Composable
internal fun DubbingSettingsCard() {
    val available by DubbingSettings.available.collectAsStateWithLifecycle()
    if (!available) return
    val enabled by DubbingSettings.enabled.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("O\u2018zbekcha ovoz", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Tarjimani ovoz bilan o\u2018qish")
                    Hint("Video davomida har bir gap o\u2018zbekcha o\u2018qiladi. Internet kerak.")
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = DubbingSettings::setEnabled,
                    modifier = Modifier.testTag("dubbing_enabled_switch"),
                )
            }
            if (enabled) {
                VoiceControls()
                VoiceTestControls()
                PacingControls()
            }
            VideoQualityControls()
            GlossaryTranslationSwitch()
        }
    }
}

@Composable
private fun Hint(
    text: String,
    modifier: Modifier = Modifier,
) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Which voice speaks, and how loud the video stays underneath it. */
@Composable
private fun VoiceControls() {
    val voiceId by DubbingSettings.voiceId.collectAsStateWithLifecycle()
    val videoVolume by DubbingSettings.videoVolumePercent.collectAsStateWithLifecycle()
    Text("Ovoz", modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DubbingSettings.voices.forEach { option ->
            FilterChip(
                selected = option.id == voiceId,
                onClick = { DubbingSettings.setVoice(option.id) },
                label = { Text(option.label) },
                modifier = Modifier.testTag("dubbing_voice_${option.id}"),
            )
        }
    }
    Text("O\u2018qiyotganda asl ovoz: $videoVolume%", modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = videoVolume.toFloat(),
        onValueChange = { DubbingSettings.setVideoVolumePercent(it.toInt()) },
        valueRange = 0f..100f,
        steps = 9,
        modifier = Modifier.testTag("dubbing_video_volume_slider"),
    )
}

/** Keeping the speech in step with the video: holding the video, speaking speed, and what happened. */
@Composable
private fun PacingControls() {
    val holdVideo by DubbingSettings.holdVideo.collectAsStateWithLifecycle()
    val maxTempo by DubbingSettings.maxTempoPercent.collectAsStateWithLifecycle()
    val stats by DubbingSettings.stats.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Ovoz tugamaguncha videoni to\u2018xtatish")
            Hint(
                "Ovoz videodan uzun bo\u2018lsa yoki internet sekin bo\u2018lsa, video bir necha soniya kutadi. " +
                    "Hech bir gap uzilmaydi va tashlab yuborilmaydi.",
            )
        }
        Switch(
            checked = holdVideo,
            onCheckedChange = DubbingSettings::setHoldVideo,
            modifier = Modifier.testTag("dubbing_hold_switch"),
        )
    }
    Text("Ovoz tezligi (eng ko\u2018pi)", modifier = Modifier.padding(top = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TEMPO_PERCENTS.forEach { percent ->
            FilterChip(
                selected = percent == maxTempo,
                onClick = { DubbingSettings.setMaxTempoPercent(percent) },
                label = { Text("${percent / PERCENT_PER_UNIT}x") },
                modifier = Modifier.testTag("dubbing_tempo_$percent"),
            )
        }
    }
    if (stats.spoken > 0) {
        Hint(
            "Shu videoda: ${stats.spoken} gap o\u2018qildi, video ${stats.held} marta jami " +
                "${stats.heldMs / MS_PER_SECOND} s kutdi, ${stats.skipped} tasi tashlandi.",
            Modifier.padding(top = 6.dp).testTag("dubbing_stats"),
        )
    }
}

/** The highest YouTube quality to load; lower quality uses much less data. */
@Composable
private fun VideoQualityControls() {
    val videoQuality by DubbingSettings.videoQuality.collectAsStateWithLifecycle()
    Text("Video sifati (trafikni tejash)", modifier = Modifier.padding(top = 10.dp))
    Hint("Pastroq sifat kamroq megabayt oladi va sekin internetda uzilmaydi. UZI tasvirlari uchun 480p.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VIDEO_QUALITIES.forEach { option ->
            FilterChip(
                selected = option.id == videoQuality,
                onClick = { DubbingSettings.setVideoQuality(option.id) },
                label = { Text(option.label) },
                modifier = Modifier.testTag("video_quality_${option.id}"),
            )
        }
    }
}

/** Glossary-guided translation: approved UZI terms are given to the translator (experiment). */
@Composable
private fun GlossaryTranslationSwitch() {
    val on by DubbingSettings.glossaryInTranslation.collectAsStateWithLifecycle()
    SpeechRuleSwitch(
        "Tarjimada UZI lug\u2018atini ishlatish (sinov)",
        "Masalan, \"transducer\" o\u2018rniga tarjimonga \"datchik\" beriladi. Yangi tarjimalarga ta\u2019sir qiladi.",
        on,
        "glossary_in_translation_switch",
        DubbingSettings::setGlossaryInTranslation,
    )
}

private const val PERCENT_PER_UNIT = 100f
private const val MS_PER_SECOND = 1_000L
