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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.translation.DEEPSEEK_MODELS
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_CLAUDE
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_GEMINI
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_GOOGLE

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
            if (!enabled) Hint("Sardor va Madina ovozlari shu tugma yoqilganda ko\u2018rinadi.")
            if (enabled) {
                VoiceControls()
                AzureVoiceControls()
                VoiceTestControls()
                PacingControls()
            }
            VideoQualityControls()
            TranslatorControls()
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

/**
 * The official Azure Speech key for the same Sardor/Madina voices, and why the voice last failed.
 * The free Microsoft endpoint can stop answering third-party apps; with a key the voices keep working.
 */
@Composable
private fun AzureVoiceControls() {
    val key by DubbingSettings.azureSpeechKey.collectAsStateWithLifecycle()
    val region by DubbingSettings.azureSpeechRegion.collectAsStateWithLifecycle()
    val status by DubbingSettings.voiceStatus.collectAsStateWithLifecycle()
    if (status.isNotBlank()) {
        Text(status, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp).testTag("voice_status"))
    }
    Text("Azure Speech (rasmiy, ixtiyoriy)", modifier = Modifier.padding(top = 10.dp))
    OutlinedTextField(
        value = key,
        onValueChange = DubbingSettings::setAzureSpeechKey,
        label = { Text("Azure Speech kaliti") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("azure_speech_key"),
    )
    OutlinedTextField(
        value = region,
        onValueChange = DubbingSettings::setAzureSpeechRegion,
        label = { Text("Hudud (masalan: westeurope)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("azure_speech_region"),
    )
    Hint(
        if (key.isBlank()) {
            "Kalitsiz Microsoft\u2019ning bepul ovozi ishlatiladi; u ba\u2019zan to\u2018xtab qoladi. Azure portalida \u201CSpeech\u201D " +
                "resursini oching (Free F0: oyiga 500 000 belgi bepul), kalit va hududni shu yerga kiriting."
        } else {
            "Sardor va Madina rasmiy Azure Speech xizmatidan o\u2018qiladi. Kalit faqat shu telefonda saqlanadi."
        },
        Modifier.padding(top = 4.dp),
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

/** Which translator works: keyless Google Translate, or Gemini with the user's own key. */
@Composable
private fun TranslatorControls() {
    val provider by DubbingSettings.translationProvider.collectAsStateWithLifecycle()
    val key by DubbingSettings.geminiApiKey.collectAsStateWithLifecycle()
    val status by DubbingSettings.translatorStatus.collectAsStateWithLifecycle()
    Text("Tarjimon", modifier = Modifier.padding(top = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = provider == TRANSLATION_PROVIDER_GOOGLE,
            onClick = { DubbingSettings.setTranslationProvider(TRANSLATION_PROVIDER_GOOGLE) },
            label = { Text("Google Translate (bepul)") },
            modifier = Modifier.testTag("translator_google"),
        )
        FilterChip(
            selected = provider == TRANSLATION_PROVIDER_GEMINI,
            onClick = { DubbingSettings.setTranslationProvider(TRANSLATION_PROVIDER_GEMINI) },
            label = { Text("Gemini") },
            modifier = Modifier.testTag("translator_gemini"),
        )
        FilterChip(
            selected = provider == TRANSLATION_PROVIDER_CLAUDE,
            onClick = { DubbingSettings.setTranslationProvider(TRANSLATION_PROVIDER_CLAUDE) },
            label = { Text("Claude") },
            modifier = Modifier.testTag("translator_claude"),
        )
    }
    if (provider == TRANSLATION_PROVIDER_CLAUDE) ClaudeKeyField()
    DeepSeekKeyField()
    if (provider == TRANSLATION_PROVIDER_GEMINI) {
        OutlinedTextField(
            value = key,
            onValueChange = DubbingSettings::setGeminiApiKey,
            label = { Text("Gemini API kaliti") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("gemini_api_key"),
        )
        Hint(
            if (key.isBlank()) {
                "Kalitni aistudio.google.com dan oling. Kalit faqat shu telefonda saqlanadi. Kalitsiz Google Translate ishlaydi."
            } else {
                "Gemini har bir darsni butunligicha, UZI lug\u2018ati bilan bitta so\u2018rovda tarjima qiladi " +
                    "(\u201CDars\u201D ekranida o\u2018zgartiriladi). " +
                    "Tayyor bo\u2018lguncha va xato bo\u2018lsa Google Translate ishlaydi."
            },
        )
    }
    if (status.isNotBlank()) Hint(status, Modifier.padding(top = 4.dp).testTag("translator_status"))
}

/**
 * The Claude key (the same one as on the "Dars" screen): Claude then translates every newly opened Uzbek
 * lesson whole, once, and stores it; Google Translate covers playback until then and on failure.
 */
@Composable
private fun ClaudeKeyField() {
    val key by DubbingSettings.claudeApiKey.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = key,
        onValueChange = DubbingSettings::setClaudeApiKey,
        label = { Text("Claude API kaliti") },
        placeholder = { Text("sk-ant-\u2026") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("claude_provider_api_key"),
    )
    Hint(
        if (key.isBlank()) {
            "Kalitni console.anthropic.com \u2192 API keys \u2192 Create Key dan oling (pullik, foydalanilgan token uchun " +
                "to\u2018lanadi). Kalit faqat shu telefonda saqlanadi."
        } else {
            "Claude har bir yangi darsni UZI lug\u2018ati bilan butunligicha bir marta tarjima qiladi va saqlaydi: " +
                "qayta ochilganda pul sarflanmaydi. Model \u201CDars\u201D ekranida tanlanadi. " +
                "Tayyor bo\u2018lguncha yoki xato bo\u2018lsa Google Translate ishlaydi."
        },
    )
}

/**
 * The DeepSeek key: when it is set, DeepSeek translates every newly opened Uzbek lesson whole, once.
 * Claude (with its key) takes over a chunk DeepSeek fails on, so the lesson still gets translated.
 */
@Composable
private fun DeepSeekKeyField() {
    val key by DubbingSettings.deepseekApiKey.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = key,
        onValueChange = DubbingSettings::setDeepseekApiKey,
        label = { Text("DeepSeek API kaliti") },
        placeholder = { Text("sk-\u2026") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("deepseek_api_key"),
    )
    Hint(
        if (key.isBlank()) {
            "Kalitni platform.deepseek.com dan oling. Kalit faqat shu telefonda saqlanadi. " +
                "Kalit kiritilsa, yangi darslar DeepSeek bilan tarjima qilinadi."
        } else {
            "Yangi darslar avval Google Translate bilan ko\u2018rsatiladi, keyin DeepSeek shu qoralamani ma\u2019no, " +
                "atama va grammatika bo\u2018yicha tuzatib, butun darsni bir marta saqlaydi. " +
                "Kalit noto\u2018g\u2018ri, balans tugagan yoki limit bo\u2018lsa tarjima to\u2018xtaydi va sababi yoziladi. " +
                "Faqat DeepSeek javobi buzilganda o\u2018sha kichik qism Claude bilan tarjima qilinadi (Claude kaliti kerak)."
        },
    )
    if (key.isNotBlank()) {
        DeepSeekModelChoice()
        ClaudeFallbackLimitField()
    }
}

/** Flash for everyday lessons; Pro when a lecture is hard. */
@Composable
private fun DeepSeekModelChoice() {
    val chosen by DubbingSettings.deepseekModel.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        DEEPSEEK_MODELS.forEach { option ->
            FilterChip(
                selected = option.id == chosen,
                onClick = { DubbingSettings.setDeepseekModel(option.id) },
                label = { Text(option.label) },
                modifier = Modifier.testTag("deepseek_model_${option.id}"),
            )
        }
    }
}

/** How many small chunks Claude may translate per lesson when DeepSeek's reply is unusable. */
@Composable
private fun ClaudeFallbackLimitField() {
    val limit by DubbingSettings.claudeFallbackLimit.collectAsStateWithLifecycle()
    // The field keeps what is typed (it may be empty for a moment); the setting takes every whole number.
    var typed by remember { mutableStateOf(limit.toString()) }
    OutlinedTextField(
        value = typed,
        onValueChange = { text ->
            typed = text.filter(Char::isDigit).take(3)
            typed.toIntOrNull()?.let(DubbingSettings::setClaudeFallbackLimit)
        },
        label = { Text("Claude zaxira limiti (bir darsda, blok)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("claude_fallback_limit"),
    )
    Hint("0 \u2014 Claude zaxirasi o\u2018chiq. Limit tugasa tarjima to\u2018xtaydi, tayyor qismi saqlanib qoladi.")
}

/** Glossary-guided translation: approved UZI terms are given to the translator. */
@Composable
private fun GlossaryTranslationSwitch() {
    val on by DubbingSettings.glossaryInTranslation.collectAsStateWithLifecycle()
    SpeechRuleSwitch(
        "Tarjimada UZI lug\u2018atini ishlatish",
        "Masalan, \"transducer\" o\u2018rniga tarjimonga \"datchik\" beriladi. O\u2018zgartirilsa, ochiq video qayta tarjima qilinadi.",
        on,
        "glossary_in_translation_switch",
        DubbingSettings::setGlossaryInTranslation,
    )
}

private const val PERCENT_PER_UNIT = 100f
private const val MS_PER_SECOND = 1_000L
