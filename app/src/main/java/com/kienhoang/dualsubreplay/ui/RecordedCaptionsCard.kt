package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.RecordingSummary
import com.kienhoang.dualsubreplay.data.describeRanges

/** What the lesson tools say about recording a video's captions from the page; null hides the card. */
internal fun recordedCaptionsHint(
    summary: RecordingSummary?,
    liveFallback: Boolean,
): String? =
    when {
        summary == null && !liveFallback -> null
        summary == null ->
            "Bu videoning subtitrini yuklab bo‘lmadi. Videoni subtitrlar paneli ochiq holda ko‘ring: YouTube ekranda " +
                "ko‘rsatgan subtitr shu yerda vaqti bilan yozib boriladi."
        summary.complete ->
            "Bu darsning subtitri ekrandan yozib olingan: ${summary.cues} qator (${describeRanges(summary.ranges)}). " +
                "Uni yuqoridagi tugmalar bilan saqlash va tarjimaga ulashish mumkin. Vaqtlar ekrandan olingani uchun " +
                "yarim soniyagacha farq qilishi mumkin."
        else ->
            "Yozildi: ${summary.cues} qator. Ko‘rilgan qismlar: ${describeRanges(summary.ranges)}. Videoni oxirigacha " +
                "ko‘ring; o‘tkazib yuborilgan joyni keyin ko‘rsangiz, u ham qo‘shiladi, qayta ko‘rilgan joy " +
                "yangilanadi. Tayyor bo‘lgach «Subtitr sifatida ishlatish» ni bosing: qayta eshitish, tarjima, " +
                "SRT saqlash va ovozli o‘qish ishlaydi."
    }

@Composable
internal fun RecordedCaptionsCard(
    summary: RecordingSummary?,
    liveFallback: Boolean,
    onUse: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    val hint = recordedCaptionsHint(summary, liveFallback) ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    LessonCard(title = "Ekrandan yozilgan subtitr", icon = Icons.Default.FiberManualRecord) {
        Hint(hint)
        if (summary != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary.complete) {
                    Button(onClick = onContinue, modifier = Modifier.testTag("continue_recording")) { Text("Yozishni davom ettirish") }
                } else {
                    Button(onClick = onUse, enabled = summary.cues > 0, modifier = Modifier.testTag("use_recording")) {
                        Text("Subtitr sifatida ishlatish")
                    }
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_recording")) {
                    Text("O‘chirish")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Yozuvni o‘chirasizmi?") },
            text = { Text("Bu videodan yozib olingan ${summary?.cues ?: 0} qator subtitr o‘chadi. Buni qaytarib bo‘lmaydi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("O‘chirish", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Bekor qilish") } },
        )
    }
}

/** One line under the live captions while a recording grows. */
internal fun recordingStatusLine(summary: RecordingSummary?): String? =
    summary?.takeIf { !it.complete && it.cues > 0 }?.let {
        "● Subtitr yozib olinmoqda: ${it.cues} qator. Menyu → «Dars: havola va subtitr» da saqlanadi."
    }
