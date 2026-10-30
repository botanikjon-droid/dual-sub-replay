package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.translation.SentenceFix
import kotlinx.coroutines.launch

/*
 * How the doctor changes one sentence's Uzbek: by hand, by asking Claude to check it, or by writing its
 * meaning in his own words for Claude to turn into a proper Uzbek sentence (when Claude alone gets it
 * wrong). Every way only fills the text field; "Saqlash" of the dialog stores it.
 */

/** Claude's answer for [sentence]; [meaning] is the doctor's own wording of what it says, or null. */
internal typealias SentenceFixer = suspend (sentence: ReviewSentence, meaning: String?) -> SentenceFix

private enum class EditMode(
    val label: String,
) {
    HAND("✏️ Qo‘lda"),
    CLAUDE("🤖 Claude bilan"),
    MEANING("💬 Ma’nosini o‘zim yozaman"),
}

@Composable
internal fun TranslationEditor(
    sentence: ReviewSentence,
    uzbek: String,
    onUzbek: (String) -> Unit,
    onMessage: (String?) -> Unit,
    fixer: SentenceFixer?,
) {
    val scope = rememberCoroutineScope()
    var mode by remember(sentence.startMs) { mutableStateOf(EditMode.HAND) }
    var meaning by remember(sentence.startMs) { mutableStateOf("") }
    var asking by remember(sentence.startMs) { mutableStateOf(false) }
    var answer by remember(sentence.startMs) { mutableStateOf<SentenceFix?>(null) }

    fun ask(note: String?) {
        val fix = fixer ?: return
        asking = true
        answer = null
        onMessage(if (note == null) "Claude tekshirmoqda…" else "Claude siz yozgan ma’noda gap tuzmoqda…")
        scope.launch {
            claudeCall { fix(sentence.copy(uzbek = uzbek), note) }
                .onSuccess { result ->
                    if (result.changed) onUzbek(result.uzbek)
                    answer = result
                    onMessage(fixMessage(result, note != null))
                }.onFailure {
                    answer = null
                    onMessage(it.message ?: "Claude javob bermadi.")
                }
            asking = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fixer != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EditMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { mode = option },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("edit_mode_${option.name.lowercase()}"),
                    )
                }
            }
        }
        if (mode == EditMode.MEANING) {
            OutlinedTextField(
                value = meaning,
                onValueChange = { meaning = it },
                minLines = 2,
                label = { Text("Gapning ma’nosi (o‘z so‘zingiz bilan)") },
                placeholder = { Text("Masalan: ilgari aorta stenozi kardiolog obro‘sini belgilaydi deyishardi") },
                modifier = Modifier.fillMaxWidth().testTag("sentence_meaning"),
            )
        }
        OutlinedTextField(
            value = uzbek,
            onValueChange = onUzbek,
            label = { Text("O‘zbekcha tarjima") },
            modifier = Modifier.fillMaxWidth().testTag("sentence_check_uzbek"),
        )
        if (asking) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (mode) {
            EditMode.HAND -> Hint("Matnni o‘zingiz tahrirlang va “Saqlash” ni bosing.")
            EditMode.CLAUDE ->
                Button(enabled = !asking, onClick = { ask(null) }, modifier = Modifier.testTag("sentence_check_claude")) {
                    Text("Claude tekshirib tuzatsin")
                }
            EditMode.MEANING ->
                Button(
                    enabled = !asking && meaning.isNotBlank(),
                    onClick = { ask(meaning.trim()) },
                    modifier = Modifier.testTag("sentence_meaning_claude"),
                ) { Text("Claude shu ma’noda yozsin") }
        }
        answer?.let { ClaudeAnswer(it) }
    }
}

/** Claude's answer right under the button: the new sentence (already in the field) and why. */
@Composable
private fun ClaudeAnswer(fix: SentenceFix) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("claude_answer")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Claude javobi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (fix.changed) {
                Text(fix.uzbek, style = MaterialTheme.typography.bodyMedium, color = FIXED_GREEN, fontWeight = FontWeight.Medium)
                if (fix.reason.isNotBlank()) Hint("Sabab: ${fix.reason}")
                Hint("Yuqoridagi maydonga qo‘yildi. Tekshirib, \u201CSaqlash\u201D ni bosing.")
            } else {
                Text("O‘zgartirish kerak emas: tarjima to‘g‘ri.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun fixMessage(
    result: SentenceFix,
    fromMeaning: Boolean,
): String =
    when {
        !result.changed && fromMeaning -> "Claude: hozirgi tarjima shu ma’noga mos."
        !result.changed -> "Claude: gap to‘g‘ri."
        else -> "Claude: ${result.reason.ifBlank { "yangi variant" }} — tekshirib, “Saqlash” ni bosing."
    }

/** What Claude is told when the doctor wrote the sentence's meaning himself. */
internal fun meaningRequest(meaning: String): String =
    "The doctor wrote in his own words what this sentence means: «$meaning». He knows the lecture; " +
        "trust his meaning over the captions. Write the Uzbek sentence with exactly this meaning, in correct " +
        "medical Uzbek with the glossary terms, and set changed=true unless the current Uzbek already says it well."
