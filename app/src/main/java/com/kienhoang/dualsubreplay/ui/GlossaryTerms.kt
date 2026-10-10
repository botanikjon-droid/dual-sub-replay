package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.GlossaryStatus
import com.kienhoang.dualsubreplay.data.GlossaryTermCheck
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.UziGlossaryStore
import com.kienhoang.dualsubreplay.data.glossaryApplies
import com.kienhoang.dualsubreplay.data.glossaryChecksForRow

/**
 * The UZI terms of a caption row as small chips under its translation ("hepatic vein → jigar
 * venasi"). Tapping one shows the approved Uzbek term and the doctor's note. The caption text
 * itself is never changed. Shown only for English captions translated into Uzbek.
 */
@Composable
internal fun GlossaryTermRow(
    segment: SubtitleSegment,
    sourceLanguage: String?,
    targetLanguage: String,
    fontScale: Float,
) {
    if (!glossaryApplies(sourceLanguage, targetLanguage)) return
    val glossary by UziGlossaryStore.glossary.collectAsStateWithLifecycle()
    val loaded = glossary ?: return
    val checks =
        remember(loaded, segment.originalText, segment.translatedText, segment.sentence) {
            glossaryChecksForRow(loaded, segment)
        }
    if (checks.isEmpty()) return
    var opened by remember(segment.id) { mutableStateOf<GlossaryTermCheck?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        checks.forEach { check ->
            GlossaryChip(check, fontScale) { opened = check }
        }
    }
    opened?.let { check -> GlossaryTermDialog(check.match.text, check.match.entry, check.approvedTermUsed) { opened = null } }
}

@Composable
private fun GlossaryChip(
    check: GlossaryTermCheck,
    fontScale: Float,
    onClick: () -> Unit,
) {
    val mismatch = check.approvedTermUsed == false
    Surface(
        modifier = Modifier.testTag("glossary_term_${check.match.entry.id}").clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (mismatch) Color(0xFF3A2A10) else Color(0xFF12343A),
        contentColor = if (mismatch) Color(0xFFFFD58A) else Color(0xFFBFEFF2),
    ) {
        Text(
            text = (if (mismatch) "⚠ " else "") + "${check.match.text} → ${shortUzbek(check.match.entry.uzbek)}",
            fontSize = (12 * fontScale).sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "umumiy o‘t yo‘li (xoledox)" stays whole; very long corrections are shortened for the chip only. */
private fun shortUzbek(uzbek: String): String =
    if (uzbek.length <=
        MAX_CHIP_UZBEK
    ) {
        uzbek
    } else {
        uzbek.take(MAX_CHIP_UZBEK - 1).trimEnd() + "…"
    }

private const val MAX_CHIP_UZBEK = 42

/** The approved Uzbek term and the doctor's note for one English term. */
@Composable
internal fun GlossaryTermDialog(
    englishText: String,
    entry: GlossaryEntry,
    approvedTermUsed: Boolean?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Yopish") } },
        title = { Text(englishText) },
        text = { GlossaryEntryDetails(entry, approvedTermUsed) },
        modifier = Modifier.testTag("glossary_term_dialog"),
    )
}

@Composable
internal fun GlossaryEntryDetails(
    entry: GlossaryEntry,
    approvedTermUsed: Boolean? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.uzbek, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
        val forms = entry.forms.filterNot { it.equals(entry.english, ignoreCase = true) }
        Text(
            "Inglizcha: ${entry.english}" + if (forms.isEmpty()) "" else " (${forms.joinToString(", ")})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${entry.category} · ${statusLabel(entry.status)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (approvedTermUsed == false) {
            Text(
                "Mashina tarjimasida bu atama boshqacha berilgan bo‘lishi mumkin. " +
                    "Lug‘atdagi tasdiqlangan shakl: ${entry.uzbek}.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB74D),
            )
        }
    }
}

internal fun statusLabel(status: GlossaryStatus): String =
    when (status) {
        GlossaryStatus.APPROVED -> "shifokor tasdiqlagan"
        GlossaryStatus.CORRECTED -> "shifokor tuzatgan"
        GlossaryStatus.NEW -> "shifokor qo‘shgan"
    }
