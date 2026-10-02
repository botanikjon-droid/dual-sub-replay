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
    val voiceId by DubbingSettings.voiceId.collectAsStateWithLifecycle()
    val videoVolume by DubbingSettings.videoVolumePercent.collectAsStateWithLifecycle()
    val videoQuality by DubbingSettings.videoQuality.collectAsStateWithLifecycle()
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
                    Text(
                        "Video davomida har bir gap o\u2018zbekcha o\u2018qiladi. Internet kerak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = DubbingSettings::setEnabled,
                    modifier = Modifier.testTag("dubbing_enabled_switch"),
                )
            }
            if (enabled) {
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
            Text("Video sifati (trafikni tejash)", modifier = Modifier.padding(top = 10.dp))
            Text(
                "Pastroq sifat kamroq megabayt oladi va sekin internetda uzilmaydi. UZI tasvirlari uchun 480p.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    }
}
