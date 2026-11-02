package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.reviewTime
import com.kienhoang.dualsubreplay.translation.SentenceFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/*
 * "Claude bilan tuzatish" of many sentences: Claude checks the marked sentences (or the whole lesson), the
 * changed ones are listed with the old and new Uzbek and the reason, the doctor unticks what he does not
 * want and applies the rest; then the lesson's konspekt and test can be rewritten from the corrected text.
 */

/** What the fix dialog needs from the app. */
internal class ClaudeFixActions(
    /** Claude's fixes of the open lesson's marked sentences (all when none is marked), with the lesson id. */
    val check: suspend () -> Pair<String, List<SentenceFix>>,
    val apply: suspend (videoId: String, fixes: List<SentenceFix>) -> String,
    val rewriteNotes: () -> Unit,
)

/** Runs a Claude call and turns its known failures into a message for the doctor. */
internal suspend fun <T> claudeCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    }

@Composable
internal fun ClaudeFixesDialog(
    actions: ClaudeFixActions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var videoId by remember { mutableStateOf<String?>(null) }
    val fixes = remember { mutableStateListOf<SentenceFix>() }
    val chosen = remember { mutableStateListOf<Int>() }
    var checked by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>("Claude gaplarni tekshirmoqda…") }
    var applied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        claudeCall { actions.check() }
            .onSuccess { (id, all) ->
                videoId = id
                checked = all.size
                val changed = all.filter { it.changed }
                fixes += changed
                chosen += changed.indices
                message = if (changed.isEmpty()) "Claude $checked gapni tekshirdi: xato topilmadi." else null
            }.onFailure { message = it.message ?: "Claude javob bermadi." }
        working = false
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (fixes.isEmpty()) "Claude bilan tuzatish" else "Claude tuzatishlari: ${fixes.size} / $checked gap") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                fixes.forEachIndexed { index, fix ->
                    FixRow(fix, index in chosen, enabled = !applied) { on -> if (on) chosen += index else chosen.remove(index) }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                if (applied) {
                    Hint("Konspekt va test eski tarjimadan yozilgan bo‘lsa, ularni tuzatilgan matndan qayta yozdiring.")
                    OutlinedButton(onClick = {
                        actions.rewriteNotes()
                        onDismiss()
                    }, modifier = Modifier.testTag("claude_fix_rewrite_notes")) { Text("Konspekt va testni Claude bilan yangilash") }
                }
            }
        },
        confirmButton = {
            if (!applied && fixes.isNotEmpty()) {
                Button(
                    enabled = !working && chosen.isNotEmpty(),
                    onClick = {
                        val id = videoId ?: return@Button
                        working = true
                        scope.launch {
                            claudeCall { actions.apply(id, chosen.sorted().map { fixes[it] }) }
                                .onSuccess {
                                    message = it
                                    applied = true
                                }.onFailure { message = it.message }
                            working = false
                        }
                    },
                    modifier = Modifier.testTag("claude_fix_apply"),
                ) { Text("Tanlanganlarni qo‘llash (${chosen.size})") }
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Yopish") } },
    )
}

@Composable
private fun FixRow(
    fix: SentenceFix,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, enabled = enabled, onCheckedChange = onSelect)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("${reviewTime(fix.sentence.startMs).substringBeforeLast(',')} · ${fix.sentence.english}")
                Text(
                    fix.sentence.uzbek,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(fix.uzbek, style = MaterialTheme.typography.bodyMedium, color = FIXED_GREEN, fontWeight = FontWeight.Medium)
                if (fix.reason.isNotBlank()) Hint("Sabab: ${fix.reason}")
            }
        }
    }
    HorizontalDivider()
}

internal val FIXED_GREEN = Color(0xFF15803D)
