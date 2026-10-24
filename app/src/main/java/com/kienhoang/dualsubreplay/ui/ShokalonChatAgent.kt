package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AppProposal
import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.ReplaceRequest
import com.kienhoang.dualsubreplay.data.ReplaceScope
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.data.reviewTime
import com.kienhoang.dualsubreplay.data.toJson
import com.kienhoang.dualsubreplay.translation.ChatBlock
import com.kienhoang.dualsubreplay.translation.ChatTools
import com.kienhoang.dualsubreplay.translation.ClaudeBackend
import com.kienhoang.dualsubreplay.translation.SentenceFix
import com.kienhoang.dualsubreplay.translation.assistantMessage
import com.kienhoang.dualsubreplay.translation.boundedToolResult
import com.kienhoang.dualsubreplay.translation.chatRequestBody
import com.kienhoang.dualsubreplay.translation.parseChatReply
import com.kienhoang.dualsubreplay.translation.shokalonChatSystem
import com.kienhoang.dualsubreplay.translation.toolResultsMessage
import com.kienhoang.dualsubreplay.translation.trimmedHistory
import com.kienhoang.dualsubreplay.translation.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** What SHOKALON chat reads from and changes in the app; AppViewModel provides it. */
internal interface ChatAppAccess {
    /** A few lines on the open lesson (or that none is open), for Claude's instructions. */
    fun context(): String

    /** The open lesson's id, title and sentences with their stored Uzbek; throws with a reason when there are none. */
    suspend fun lessonSentences(): Triple<String, String?, List<ReviewSentence>>

    suspend fun lessonNotes(): LessonNotes?

    fun catalog(): String

    /** How many places a replacement would change (nothing is changed). */
    suspend fun previewReplace(request: ReplaceRequest): String

    suspend fun applyFixes(
        videoId: String,
        fixes: List<SentenceFix>,
    ): String

    suspend fun applyReplace(request: ReplaceRequest): String

    /** Starts rewriting the open lesson's notes with Claude. */
    fun rewriteNotes(): String

    suspend fun recordProposal(proposal: AppProposal)

    fun backend(): ClaudeBackend
}

internal enum class ProposalState { WAITING, WORKING, DONE, FAILED }

/** One row of the conversation as the doctor sees it. */
internal sealed interface ChatItem {
    val id: Long

    data class Doctor(
        override val id: Long,
        val text: String,
    ) : ChatItem

    data class Assistant(
        override val id: Long,
        val text: String,
    ) : ChatItem

    data class Fix(
        override val id: Long,
        val videoId: String,
        val fix: SentenceFix,
        val state: ProposalState = ProposalState.WAITING,
        val result: String? = null,
    ) : ChatItem

    data class Replace(
        override val id: Long,
        val request: ReplaceRequest,
        val preview: String,
        val state: ProposalState = ProposalState.WAITING,
        val result: String? = null,
    ) : ChatItem

    data class Notes(
        override val id: Long,
        val why: String,
        val state: ProposalState = ProposalState.WAITING,
        val result: String? = null,
    ) : ChatItem

    data class Proposal(
        override val id: Long,
        val proposal: AppProposal,
    ) : ChatItem

    data class Notice(
        override val id: Long,
        val text: String,
    ) : ChatItem
}

/**
 * The conversation loop: the doctor's message goes to Claude with the tools; tool calls are answered from
 * the app (reading) or turned into proposal rows (changing), until Claude answers in words. At most
 * [MAX_STEPS] requests per message, so a confused answer cannot spend the key's budget.
 */
internal class ShokalonChatAgent(
    private val scope: CoroutineScope,
    private val access: ChatAppAccess,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val messages = mutableListOf<JSONObject>()
    private val ids = AtomicLong()
    private var job: Job? = null

    /** Sentences as numbered in the last answer to get_lesson_sentences, and their lesson. */
    private var numbered: Pair<String, List<ReviewSentence>>? = null

    private fun add(item: ChatItem) = _items.update { it + item }

    private fun nextId() = ids.incrementAndGet()

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || job?.isActive == true) return
        add(ChatItem.Doctor(nextId(), clean))
        messages += userMessage(clean)
        _running.value = true
        job =
            scope.launch {
                try {
                    converse()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: java.io.IOException) {
                    add(ChatItem.Notice(nextId(), error.message ?: "Claude javob bermadi."))
                } catch (error: IllegalArgumentException) {
                    add(ChatItem.Notice(nextId(), error.message ?: "Claude API kalitini kiriting."))
                } finally {
                    _running.value = false
                }
            }
    }

    fun stop() {
        job?.cancel()
        _running.value = false
        // An unanswered tool call would make the next request invalid: start the next message cleanly.
        while (messages.isNotEmpty() && messages.last().opt("content") !is String) messages.removeAt(messages.lastIndex)
        if (messages.isNotEmpty()) messages.removeAt(messages.lastIndex)
        add(ChatItem.Notice(nextId(), "To‘xtatildi."))
    }

    fun clear() {
        job?.cancel()
        _running.value = false
        messages.clear()
        numbered = null
        _items.value = emptyList()
    }

    private suspend fun converse() {
        val backend = access.backend()
        repeat(MAX_STEPS) {
            val body = chatRequestBody(backend.model, shokalonChatSystem(access.context()), JSONArray(trimmedHistory(messages)))
            val reply = parseChatReply(backend.request(body))
            messages += assistantMessage(reply.content)
            val results = mutableListOf<Pair<String, String>>()
            for (block in reply.blocks) {
                when (block) {
                    is ChatBlock.Text -> add(ChatItem.Assistant(nextId(), block.text))
                    is ChatBlock.ToolUse -> results += block.id to boundedToolResult(runTool(block.name, block.input))
                }
            }
            if (results.isEmpty()) return
            messages += toolResultsMessage(results)
        }
        add(ChatItem.Notice(nextId(), "Claude juda ko‘p qadam qildi; savolni aniqroq yozing."))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTool(
        name: String,
        input: JSONObject,
    ): String =
        try {
            when (name) {
                ChatTools.LESSON_SENTENCES -> sentences(input)
                ChatTools.LESSON_NOTES ->
                    access
                        .lessonNotes()
                        ?.copy(frames = emptyList())
                        ?.toJson()
                        ?.toString()
                        ?: "The open lesson has no notes yet."
                ChatTools.CATALOG -> access.catalog()
                ChatTools.PROPOSE_FIXES -> proposeFixes(input)
                ChatTools.PROPOSE_REPLACE -> proposeReplace(input)
                ChatTools.PROPOSE_NOTES -> {
                    add(ChatItem.Notes(nextId(), input.optString("why")))
                    "Offered to the doctor as a button."
                }
                ChatTools.REPORT_ISSUE -> recordProposal(input)
                else -> "Unknown tool $name."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            "Error: ${error.message ?: error.javaClass.simpleName}"
        }

    private suspend fun numberedSentences(): Pair<String, List<ReviewSentence>> =
        numbered ?: access.lessonSentences().let { (id, _, sentences) -> (id to sentences).also { numbered = it } }

    private suspend fun sentences(input: JSONObject): String {
        val (videoId, title, list) = access.lessonSentences()
        numbered = videoId to list
        val from = input.optInt("from", 1).coerceIn(1, list.size.coerceAtLeast(1))
        val to = input.optInt("to", from + PAGE - 1).coerceIn(from, minOf(list.size, from + PAGE - 1))
        return buildString {
            append("Lesson: ")
                .append(title ?: videoId)
                .append(" — ")
                .append(list.size)
                .append(" sentences\n")
            for (number in from..to) {
                val sentence = list.getOrNull(number - 1) ?: break
                append('[')
                    .append(number)
                    .append("] ")
                    .append(reviewTime(sentence.startMs).substringBeforeLast(','))
                    .append('\n')
                append("EN: ")
                    .append(sentence.english)
                    .append("\nUZ: ")
                    .append(sentence.uzbek)
                    .append('\n')
            }
        }
    }

    private suspend fun proposeFixes(input: JSONObject): String {
        val (videoId, list) = numberedSentences()
        val array = input.optJSONArray("fixes") ?: JSONArray()
        var shown = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val sentence = list.getOrNull(item.optInt("n") - 1) ?: continue
            val uzbek = item.optString("uzbek").trim()
            if (uzbek.isEmpty() || uzbek == sentence.uzbek.trim()) continue
            add(ChatItem.Fix(nextId(), videoId, SentenceFix(sentence, uzbek, item.optString("reason").trim(), changed = true)))
            shown++
        }
        return "Shown $shown fixes to the doctor; each is applied only when he taps it."
    }

    private suspend fun proposeReplace(input: JSONObject): String {
        val scope =
            when (input.optString("scope")) {
                "all" -> ReplaceScope.ALL
                "section" -> ReplaceScope.SECTION
                else -> ReplaceScope.LESSON
            }
        val request = ReplaceRequest(input.optString("find"), input.optString("replacement"), input.optBoolean("whole_word", true), scope)
        val preview = access.previewReplace(request)
        add(ChatItem.Replace(nextId(), request, preview))
        return "Preview shown to the doctor: $preview"
    }

    private suspend fun recordProposal(input: JSONObject): String {
        val proposal = AppProposal(input.optString("kind", "taklif"), input.optString("title"), input.optString("details"), now())
        access.recordProposal(proposal)
        add(ChatItem.Proposal(nextId(), proposal))
        return "Recorded in the doctor's list for the developer."
    }

    /** Applies a proposal row the doctor tapped. */
    fun apply(item: ChatItem) {
        mark(item.id, ProposalState.WORKING, null)
        scope.launch {
            val (state, result) =
                try {
                    ProposalState.DONE to
                        when (item) {
                            is ChatItem.Fix -> access.applyFixes(item.videoId, listOf(item.fix))
                            is ChatItem.Replace -> access.applyReplace(item.request)
                            is ChatItem.Notes -> access.rewriteNotes()
                            else -> ""
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IllegalStateException) {
                    ProposalState.FAILED to error.message
                } catch (error: IllegalArgumentException) {
                    ProposalState.FAILED to error.message
                } catch (error: java.io.IOException) {
                    ProposalState.FAILED to error.message
                }
            mark(item.id, state, result)
        }
    }

    private fun mark(
        id: Long,
        state: ProposalState,
        result: String?,
    ) {
        _items.update { list ->
            list.map {
                when {
                    it.id != id -> it
                    it is ChatItem.Fix -> it.copy(state = state, result = result)
                    it is ChatItem.Replace -> it.copy(state = state, result = result)
                    it is ChatItem.Notes -> it.copy(state = state, result = result)
                    else -> it
                }
            }
        }
    }

    private companion object {
        const val MAX_STEPS = 8
        const val PAGE = 120
    }
}
