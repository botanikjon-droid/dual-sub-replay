package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.AsrFixStore
import kotlinx.coroutines.launch

/*
 * "Noto‘g‘ri eshitilgan so‘zlar": the doctor adds a word the automatic captions keep mishearing ("doubler" ->
 * "Doppler"); it is fixed in the English of every lesson translated from then on. A shipped list works already.
 */

@Composable
internal fun AsrFixesCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AsrFixStore.load(context) }
    val own by AsrFixStore.own.collectAsStateWithLifecycle()
    var heard by remember { mutableStateOf("") }
    var correct by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LessonCard(title = "Noto‘g‘ri eshitilgan so‘zlar", icon = Icons.Default.Hearing) {
        Hint(
            "YouTube subtitri ba’zi so‘zlarni noto‘g‘ri yozadi (ayniqsa hind talaffuzida: “doubler” = Doppler). " +
                "Bunday so‘zlar tarjimadan oldin inglizchada tuzatiladi. Tayyor ro‘yxat bor; o‘zingiz ko‘rganlarini qo‘shing. " +
                "Yangi qo‘shilgan so‘z keyin tarjima qilinadigan darslarda ishlaydi.",
        )
        OutlinedTextField(
            value = heard,
            onValueChange = { heard = it },
            singleLine = true,
            label = { Text("Subtitrda shunday yozilgan (masalan: doubler)") },
            modifier = Modifier.fillMaxWidth().testTag("asr_heard"),
        )
        OutlinedTextField(
            value = correct,
            onValueChange = { correct = it },
            singleLine = true,
            label = { Text("To‘g‘risi (masalan: Doppler)") },
            modifier = Modifier.fillMaxWidth().testTag("asr_correct"),
        )
        Button(
            enabled = heard.isNotBlank() && correct.isNotBlank(),
            modifier = Modifier.padding(top = 4.dp).testTag("asr_add"),
            onClick = {
                scope.launch {
                    message =
                        try {
                            AsrFixStore.add(heard, correct)
                            "Qo‘shildi: “${heard.trim()}” → “${correct.trim()}”.".also {
                                heard = ""
                                correct = ""
                            }
                        } catch (error: IllegalArgumentException) {
                            error.message
                        }
                }
            },
        ) { Text("Qo‘shish") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        own.forEach { fix ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("“${fix.heard}” → “${fix.correct}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { AsrFixStore.remove(fix) } }) {
                    Icon(Icons.Default.Close, contentDescription = "O‘chirish")
                }
            }
        }
    }
}
