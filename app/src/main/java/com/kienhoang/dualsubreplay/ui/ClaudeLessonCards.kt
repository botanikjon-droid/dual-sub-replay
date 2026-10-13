package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.LearnedGlossaryStore
import com.kienhoang.dualsubreplay.data.LearnedTerm
import com.kienhoang.dualsubreplay.data.LearnedTermStatus
import com.kienhoang.dualsubreplay.data.approveLearnedTerm
import com.kienhoang.dualsubreplay.data.learnedTermKey
import com.kienhoang.dualsubreplay.data.learnedTermsForReview
import com.kienhoang.dualsubreplay.data.removeLearnedTerm
import com.kienhoang.dualsubreplay.data.upsertDoctorTerm
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.CLAUDE_MODELS
import kotlinx.coroutines.launch

/** Doubtful places listed under the Claude card; the rest are in the count. */
private const val SHOWN_DOUBTS = 8

/** Learned terms listed at once; the doctor can show all. */
private const val SHOWN_TERMS = 12

/**
 * "Claude bilan tarjima": the doctor's API key and model, the button for the open lesson, the
 * automatic mode for every newly opened lesson, progress, and the places Claude marked as doubtful.
 * Shown only in the build with Uzbek voice settings (the "uz" build).
 */
@Composable
internal fun ClaudeTranslateCard(
    activeVideoId: String?,
    run: ClaudeRunState,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
) {
    val available by DubbingSettings.available.collectAsStateWithLifecycle()
    if (!available) return
    val key by DubbingSettings.claudeApiKey.collectAsStateWithLifecycle()
    val model by DubbingSettings.claudeModel.collectAsStateWithLifecycle()
    val auto by DubbingSettings.claudeAuto.collectAsStateWithLifecycle()
    val runningHere = run.running && run.videoId == activeVideoId
    LessonCard(title = "Claude bilan tarjima", icon = Icons.Default.AutoAwesome) {
        Hint(
            "Claude butun darsni UZI lug‘ati va tarjima qoidalari bilan tarjima qiladi. Tayyor bo‘lgach, tarjima shu darsga " +
                "yuklanadi va ovozda o‘qiladi. Kalit faqat shu telefonda saqlanadi; har bir dars uchun API hisobingizdan haq olinadi.",
        )
        OutlinedTextField(
            value = key,
            onValueChange = DubbingSettings::setClaudeApiKey,
            label = { Text("Claude API kaliti") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("claude_api_key"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CLAUDE_MODELS.forEach { option ->
                FilterChip(
                    selected = model == option.id,
                    onClick = { DubbingSettings.setClaudeModel(option.id) },
                    label = { Text(option.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Har bir yangi darsni avtomatik tarjima qilish", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = DubbingSettings::setClaudeAuto, modifier = Modifier.testTag("claude_auto"))
        }
        if (runningHere) {
            LinearProgressIndicator(
                progress = { if (run.total > 0) run.done.toFloat() / run.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("${run.done} / ${run.total} gap tarjima qilindi. Video ko‘rishda davom etishingiz mumkin.")
            OutlinedButton(onClick = onCancel) { Text("To‘xtatish") }
        } else {
            Button(
                enabled = activeVideoId != null && key.isNotBlank() && !run.running,
                modifier = Modifier.fillMaxWidth().testTag("claude_translate_lesson"),
                onClick = onTranslate,
            ) { Text("Shu darsni Claude bilan tarjima qilish") }
        }
        if (run.videoId == activeVideoId) {
            run.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("claude_message")) }
            ClaudeDoubts(run.doubts)
        }
    }
}

@Composable
private fun ClaudeDoubts(doubts: List<ClaudeDoubt>) {
    if (doubts.isEmpty()) return
    Text("Tekshirish kerak (asl audioni tinglang):", fontWeight = FontWeight.SemiBold)
    doubts.take(SHOWN_DOUBTS).forEach { doubt ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("${doubt.index + 1}. ${doubt.english}", style = MaterialTheme.typography.bodySmall)
            Text(doubt.uzbek, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            val options = if (doubt.options.isEmpty()) "" else " → ${doubt.options.joinToString(" / ")}"
            Hint("Eshitilgan: “${doubt.heard}”$options. ${doubt.reason}")
        }
    }
    if (doubts.size > SHOWN_DOUBTS) Hint("Yana ${doubts.size - SHOWN_DOUBTS} ta joy.")
}

/**
 * The glossary that grows while lessons are watched: terms Claude suggested wait for the doctor's
 * approval, edit or deletion, and the doctor can add terms by hand. Approved terms are rules for
 * every later Claude translation; pending ones are used as suggestions.
 */
@Composable
internal fun LearnedGlossaryCard() {
    val available by DubbingSettings.available.collectAsStateWithLifecycle()
    if (!available) return
    val terms by LearnedGlossaryStore.terms.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LearnedTerm?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val pending = terms.count { it.status == LearnedTermStatus.PENDING }
    val ordered = learnedTermsForReview(terms)

    fun change(block: (List<LearnedTerm>) -> List<LearnedTerm>) {
        scope.launch { LearnedGlossaryStore.update(block) }
    }

    LessonCard(title = "O‘sib boruvchi lug‘at", icon = Icons.Default.School) {
        Hint(
            if (terms.isEmpty()) {
                "Claude tarjima qilganda UZI lug‘atida yo‘q atamalarni shu yerga taklif qiladi. Ularni tasdiqlang yoki tuzating; " +
                    "tasdiqlangan atamalar keyingi barcha darslarda ishlatiladi."
            } else {
                "${terms.size} ta atama, $pending tasi tasdiq kutmoqda. ✓ tasdiqlash, ✎ tuzatish, 🗑 o‘chirish."
            },
        )
        (if (showAll) ordered else ordered.take(SHOWN_TERMS)).forEach { term ->
            LearnedTermRow(
                term = term,
                onApprove = { change { approveLearnedTerm(it, term.english) } },
                onEdit = { editing = term },
                onDelete = { change { removeLearnedTerm(it, term.english) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = LearnedTerm("", "") }, modifier = Modifier.testTag("add_learned_term")) {
                Text("Atama qo‘shish")
            }
            if (ordered.size > SHOWN_TERMS) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Kamroq" else "Hammasi (${ordered.size})") }
            }
        }
    }
    editing?.let { term ->
        LearnedTermDialog(
            term = term,
            onSave = { english, uzbek, note ->
                change { existing ->
                    val renamed =
                        if (term.english.isNotBlank() &&
                            term.key != learnedTermKey(english)
                        ) {
                            removeLearnedTerm(existing, term.english)
                        } else {
                            existing
                        }
                    upsertDoctorTerm(renamed, english, uzbek, note)
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun LearnedTermRow(
    term: LearnedTerm,
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${term.english} → ${term.uzbek}", style = MaterialTheme.typography.bodyMedium)
            val state = if (term.status == LearnedTermStatus.PENDING) "tasdiq kutmoqda" else "tasdiqlangan"
            val seen = if (term.seen > 1) " · ${term.seen} darsda" else ""
            Hint("$state$seen${if (term.note.isNotBlank()) " · ${term.note}" else ""}")
        }
        if (term.status == LearnedTermStatus.PENDING) {
            IconButton(onClick = onApprove) { Icon(Icons.Default.Check, contentDescription = "Tasdiqlash") }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Tuzatish") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "O‘chirish") }
    }
}

@Composable
private fun LearnedTermDialog(
    term: LearnedTerm,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var english by remember(term) { mutableStateOf(term.english) }
    var uzbek by remember(term) { mutableStateOf(term.uzbek) }
    var note by remember(term) { mutableStateOf(term.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (term.english.isBlank()) "Yangi atama" else "Atamani tuzatish") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = english, onValueChange = { english = it }, label = { Text("Inglizcha") }, singleLine = true)
                OutlinedTextField(value = uzbek, onValueChange = { uzbek = it }, label = { Text("O‘zbekcha") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Izoh (ixtiyoriy)") })
            }
        },
        confirmButton = {
            TextButton(enabled = english.isNotBlank() && uzbek.isNotBlank(), onClick = { onSave(english, uzbek, note) }) {
                Text("Saqlash")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor qilish") } },
    )
}
