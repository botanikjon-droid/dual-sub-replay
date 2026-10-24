package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.AppProposalStore
import com.kienhoang.dualsubreplay.data.appProposalsText
import com.kienhoang.dualsubreplay.data.reviewTime
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/*
 * The screen of SHOKALON chat: the conversation, proposal rows with a button each, a text field, and the
 * list of app faults and wishes to share with the developer.
 */

/** True while the chat is open; the top bar and the home header open it. */
internal val shokalonChatOpen = MutableStateFlow(false)

/** The chat button of the lesson's top bar (SHOKALON only). */
@Composable
internal fun RowScope.ChatButton() {
    if (!shokalonFocusMode) return
    IconButton(
        onClick = { shokalonChatOpen.value = true },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("shokalon_chat_open"),
    ) { Icon(Icons.Default.Forum, contentDescription = "SHOKALON chat") }
}

@Composable
internal fun ShokalonChatHost(agent: () -> ShokalonChatAgent) {
    val open by shokalonChatOpen.collectAsStateWithLifecycle()
    if (!open) return
    ShokalonChatScreen(agent()) { shokalonChatOpen.value = false }
}

private val STARTERS =
    listOf(
        "Shu darsning tarjimasini tekshirib, xatolarni top",
        "Shu dars mavzusini qisqa tushuntirib ber",
        "Qaysi bo‘limlarda tarjima yoki konspekt yetishmaydi?",
        "Dasturga taklif yozmoqchiman",
    )

@Composable
private fun ShokalonChatScreen(
    agent: ShokalonChatAgent,
    onDismiss: () -> Unit,
) {
    val items by agent.items.collectAsStateWithLifecycle()
    val running by agent.running.collectAsStateWithLifecycle()
    val claudeKey by DubbingSettings.claudeApiKey.collectAsStateWithLifecycle()
    val proposals by AppProposalStore.items.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var showProposals by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.lastIndex) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("shokalon_chat"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SHOKALON chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint("Claude · tarjima, konspekt va dastur bo‘yicha yordamchi")
                    }
                    TextButton(onClick = { showProposals = true }) { Text("Takliflar (${proposals.size})") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Yopish") }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (items.isEmpty()) item { ChatIntro(claudeKey.isNotBlank()) { text = it } }
                    items(items, key = { it.id }) { item -> ChatRow(item, agent::apply) }
                }
                ChatInput(
                    text,
                    running,
                    enabled = claudeKey.isNotBlank(),
                    onText = { text = it },
                    onStop = agent::stop,
                    onClear = agent::clear,
                ) {
                    agent.send(text)
                    text = ""
                }
            }
        }
    }
    if (showProposals) ProposalsDialog { showProposals = false }
}

@Composable
private fun ChatIntro(
    hasKey: Boolean,
    onStarter: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Savol yozing yoki topshiriq bering. Claude ochiq darsni o‘qiydi, xatolarni topadi va tuzatishni taklif qiladi — " +
                "har bir o‘zgarish faqat siz tugmani bosganingizda qo‘llanadi. Dastur bo‘yicha xato va takliflar " +
                "“Takliflar” ro‘yxatiga yoziladi: uni ishlab chiquvchiga ulashasiz.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!hasKey) Text("Avval sozlamalarda Claude API kalitini kiriting.", color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            STARTERS.forEach { starter -> OutlinedButton(onClick = { onStarter(starter) }) { Text(starter) } }
        }
        Hint("Har bir xabar Claude kalitingizdan 1–8 so‘rov sarflaydi.")
    }
}

@Composable
private fun ChatInput(
    text: String,
    running: Boolean,
    enabled: Boolean,
    onText: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClear, enabled = !running) { Text("Yangi") }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            enabled = enabled,
            placeholder = { Text("Xabar…") },
            maxLines = 5,
            modifier = Modifier.weight(1f).testTag("shokalon_chat_input"),
        )
        if (running) {
            TextButton(onClick = onStop) { Text("To‘xtat") }
        } else {
            IconButton(enabled = enabled && text.isNotBlank(), onClick = onSend, modifier = Modifier.testTag("shokalon_chat_send")) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Yuborish")
            }
        }
    }
}

@Composable
private fun ChatRow(
    item: ChatItem,
    onApply: (ChatItem) -> Unit,
) {
    when (item) {
        is ChatItem.Doctor -> Bubble(item.text, mine = true)
        is ChatItem.Assistant -> Bubble(item.text, mine = false)
        is ChatItem.Notice -> Hint(item.text)
        is ChatItem.Fix ->
            ProposalCard("Gapni tuzatish · ${reviewTime(item.fix.sentence.startMs).substringBeforeLast(',')}", item.state, item.result) {
                Text(item.fix.sentence.uzbek, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(item.fix.uzbek, style = MaterialTheme.typography.bodyMedium, color = FIXED_GREEN, fontWeight = FontWeight.Medium)
                if (item.fix.reason.isNotBlank()) Hint("Sabab: ${item.fix.reason}")
                ApplyButton(item.state, "Qo‘llash") { onApply(item) }
            }
        is ChatItem.Replace ->
            ProposalCard("Almashtirish · ${item.request.scope.label}", item.state, item.result) {
                Text("“${item.request.find}” → “${item.request.replacement}”", fontWeight = FontWeight.Medium)
                Hint(item.preview)
                ApplyButton(item.state, "Almashtirish") { onApply(item) }
            }
        is ChatItem.Notes ->
            ProposalCard("Konspekt va testni yangilash", item.state, item.result) {
                if (item.why.isNotBlank()) Hint(item.why)
                ApplyButton(item.state, "Claude bilan qayta yozish") { onApply(item) }
            }
        is ChatItem.Proposal ->
            ProposalCard("Dastur uchun ${item.proposal.kind}: ${item.proposal.title}", ProposalState.DONE, null) {
                Text(item.proposal.details, style = MaterialTheme.typography.bodySmall)
                Hint("“Takliflar” ro‘yxatiga yozildi.")
            }
    }
}

@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(if (mine) BUBBLE_WIDTH_MINE else 1f),
        ) {
            SelectionContainer { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private const val BUBBLE_WIDTH_MINE = 0.85f

@Composable
private fun ProposalCard(
    title: String,
    state: ProposalState,
    result: String?,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
            result?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (state ==
                            ProposalState.FAILED
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                )
            }
        }
    }
}

@Composable
private fun ApplyButton(
    state: ProposalState,
    label: String,
    onClick: () -> Unit,
) {
    when (state) {
        ProposalState.WAITING, ProposalState.FAILED -> Button(onClick = onClick) { Text(label) }
        ProposalState.WORKING -> LinearProgressIndicator(Modifier.fillMaxWidth())
        ProposalState.DONE -> Text("✓ Qo‘llandi", color = FIXED_GREEN, fontWeight = FontWeight.SemiBold)
    }
}

/** The doctor's list of app faults and wishes: share it with the developer, copy it, or clear it. */
@Composable
private fun ProposalsDialog(onDismiss: () -> Unit) {
    val items by AppProposalStore.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val text = appProposalsText(items)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dastur takliflari (${items.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Hint("Hali yozilmagan. Chatda dasturdagi xato yoki yangi funksiya haqida yozing — Claude shu yerga qo‘shadi.")
                } else {
                    items.takeLast(MAX_SHOWN).forEach { Text("• [${it.kind}] ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                    Hint("Ro‘yxatni ulashing yoki nusxalab, ishlab chiquvchi (Claude) bilan suhbatga yuboring.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val send =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                            context.startActivity(Intent.createChooser(send, "Takliflarni ulashish"))
                        }) { Text("Ulashish") }
                        OutlinedButton(
                            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SHOKALON", text))) } },
                        ) {
                            Text("Nusxa")
                        }
                        TextButton(onClick = { scope.launch { AppProposalStore.clear() } }) { Text("Tozalash") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Yopish") } },
    )
}

private const val MAX_SHOWN = 30
