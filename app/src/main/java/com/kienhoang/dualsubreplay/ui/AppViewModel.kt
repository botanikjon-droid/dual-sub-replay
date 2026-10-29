package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.ApiUsageLog
import com.kienhoang.dualsubreplay.data.AppProposal
import com.kienhoang.dualsubreplay.data.AppProposalStore
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.ImmersionAccumulator
import com.kienhoang.dualsubreplay.data.ImmersionRepository
import com.kienhoang.dualsubreplay.data.ImmersionTimeTracker
import com.kienhoang.dualsubreplay.data.JapaneseDictionaryStore
import com.kienhoang.dualsubreplay.data.JapaneseMorphology
import com.kienhoang.dualsubreplay.data.immersionLanguage
import com.kienhoang.dualsubreplay.data.initialDailyGoalPromptCompleted
import com.kienhoang.dualsubreplay.data.pendingCorrectionsFromJson
import com.kienhoang.dualsubreplay.data.pendingCorrectionsToJson
import com.kienhoang.dualsubreplay.data.splitCuesAtSentenceEnds
import com.kienhoang.dualsubreplay.data.storedDailyGoalMinutes
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.LessonProgressFiles
import com.kienhoang.dualsubreplay.data.LessonRecord
import com.kienhoang.dualsubreplay.data.NoteFrame
import com.kienhoang.dualsubreplay.data.addManualFrame
import com.kienhoang.dualsubreplay.data.formatVideoTime
import com.kienhoang.dualsubreplay.data.withFrameImage
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.RecentCaptionTracks
import com.kienhoang.dualsubreplay.data.RecordedCaptionStore
import com.kienhoang.dualsubreplay.data.RecordingSummary
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.YouTubeCaptionProvider
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.data.applyImportedTranslation
import com.kienhoang.dualsubreplay.data.formatSrt
import com.kienhoang.dualsubreplay.data.lessonHeader
import com.kienhoang.dualsubreplay.data.mergeImportedCues
import com.kienhoang.dualsubreplay.data.parseSubtitleFile
import com.kienhoang.dualsubreplay.data.readBoundedText
import com.kienhoang.dualsubreplay.data.toSegments
import com.kienhoang.dualsubreplay.data.ReplaceScope
import com.kienhoang.dualsubreplay.data.ReplaceRequest
import com.kienhoang.dualsubreplay.data.CueReplacement
import com.kienhoang.dualsubreplay.data.replaceInCues
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.data.reviewFileVideoId
import com.kienhoang.dualsubreplay.data.reviewFileText
import com.kienhoang.dualsubreplay.data.parseReviewFile
import com.kienhoang.dualsubreplay.data.applySentenceCorrection
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.UziGlossaryStore
import com.kienhoang.dualsubreplay.data.translationRequest
import com.kienhoang.dualsubreplay.data.retireLegacyDownloadJobs
import com.kienhoang.dualsubreplay.data.savedWordFrom
import com.kienhoang.dualsubreplay.data.subtitleFileName
import com.kienhoang.dualsubreplay.data.SHOKALON_IDS_ASSET
import com.kienhoang.dualsubreplay.data.SHOKALON_TRANSLATION_SOURCE
import com.kienhoang.dualsubreplay.data.parseShokalonIds
import com.kienhoang.dualsubreplay.data.readTranslationZip
import com.kienhoang.dualsubreplay.data.translationZipSummary
import com.kienhoang.dualsubreplay.dubbing.DubbingController
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.dubbing.LessonVoicePrefetch
import com.kienhoang.dualsubreplay.dubbing.createDubbingVoice
import com.kienhoang.dualsubreplay.translation.ClaudeBackend
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.SentenceFix
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.glossaryForBatch
import com.kienhoang.dualsubreplay.translation.requestSentenceFixes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import com.kienhoang.dualsubreplay.data.activeWordIndex as timedActiveWordIndex

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

data class DualSubUiState(
    val liveFallback: Boolean = false,
    val liveOriginal: String? = null,
    val liveTranslated: String? = null,
    val retryingTranscript: Boolean = false,
    val browserUrl: String = YOUTUBE_HOME_URL,
    val browserNavigationRequestId: Long = 0L,
    val activeVideoId: String? = null,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val targetLanguage: String = "vi",
    val onboardingCompleted: Boolean = false,
    val guideCompleted: Boolean = false,
    val dailyGoalPromptCompleted: Boolean = false,
    val dailyGoalMinutes: Int = 0,
    val availableSourceLanguages: List<CaptionLanguage> = emptyList(),
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val playbackPaused: Boolean = false,
    val originalVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val translatedVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val currentIndex: Int = -1,
    val activeWordIndex: Int = -1,
    val fontScale: Float = 1f,
    val portraitPanelOffsetFraction: Float = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
    val landscapeSplitEnabled: Boolean = true,
    val originalColorKey: String = DEFAULT_ORIGINAL_COLOR_KEY,
    val translatedColorKey: String = DEFAULT_TRANSLATED_COLOR_KEY,
    val highlightColorKey: String = DEFAULT_HIGHLIGHT_COLOR_KEY,
    val wordHighlightEnabled: Boolean = true,
    val customColorsEnabled: Boolean = true,
    val captionFormat: CaptionFormat = CaptionFormat.SHORT_PHRASES,
    val isDownloadingTranslationModel: Boolean = false,
    val lockOverlayToVideo: Boolean = false,
    val preloadModelsEnabled: Boolean = true,
    val naturalSubtitlesEnabled: Boolean = true,
    val wordLearningEnabled: Boolean = true,
    val wordLearningTarget: String = "both",
    val wordLearningActiveOnly: Boolean = true,
    val tapToLearnEnabled: Boolean = true,
    val selectedLearningWord: LearningWordSelection? = null,
    val autoPronounce: Boolean = true,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /** Why translation stopped while the original captions keep playing, or null. */
    val translationError: String? = null,
    /** Cues in the user's imported translation for the open video; 0 when none is imported. */
    val importedTranslationCues: Int = 0,
    /** Captions recorded from the YouTube page for the open video (videos without a downloadable track), or null. */
    val recordedCaptions: RecordingSummary? = null,
)

/**
 * A source-language choice is only rejected when the video's track list is
 * known AND clearly does not contain the requested language. While a load is
 * still in flight (empty list) every valid selection must be accepted so
 * changing the subtitle language mid-video always takes effect (issue #20).
 */
internal fun shouldAcceptSourcePreference(
    requested: String,
    availableSourceLanguages: List<CaptionLanguage>,
): Boolean {
    val normalized = TranslationLanguages.normalize(requested)
    if (normalized == "auto") return true
    if (availableSourceLanguages.isEmpty()) return true
    return availableSourceLanguages.any {
        TranslationLanguages.normalize(it.code) == normalized
    }
}

/** Playback tracking only restarts from zero when a different video loads. */
internal fun shouldResetPlaybackClock(
    previousVideoId: String?,
    newVideoId: String,
): Boolean = previousVideoId != newVideoId

internal fun activeSubtitleIndex(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = segments.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (segments[middle].startMs <= timeMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return candidate.takeIf { it >= 0 && timeMs < segments[it].endMs } ?: -1
}

internal fun nearestSegmentIndex(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = segments.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (segments[middle].startMs <= timeMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return candidate.coerceAtLeast(0)
}

/** Word currently being spoken inside [segmentIndex]; -1 when none is tracked. */
internal fun activeWordIndex(
    segments: List<SubtitleSegment>,
    segmentIndex: Int,
    timeMs: Long,
): Int =
    segments
        .getOrNull(segmentIndex)
        ?.takeIf { segment -> segment.startMs <= timeMs && timeMs < segment.endMs }
        ?.let { segment -> timedActiveWordIndex(segment.words, timeMs) }
        ?: -1

internal const val YOUTUBE_HOME_URL = "https://m.youtube.com/"
private const val NO_VIDEO_MESSAGE = "Avval dars videosini oching."
private const val REVIEW_MARKS_PREFIX = "review_marks_"
private const val PENDING_FIX_PREFIX = "pending_fix_"
private const val FULL_GLOSSARY_SENTENCES = 5
private const val MAX_REPLACE_EXAMPLES = 5
private const val NO_STORED_TRANSLATION_MESSAGE = "Bu darsning saqlangan tarjimasi yo\u2018q: avval tarjima qiling yoki ZIP\u2019ni yuklang."
private const val REPLACE_WAIT_MESSAGE =
    "Bu darsning tarjimasi hali saqlanmagan: tarjima tugashini kuting (yuqoridagi \u23F3 yozuvi yo\u2018qolsin), keyin almashtiring."
private const val MS_PER_SECOND = 1_000L

/** Lesson translations in progress, chunk by chunk, until the whole lesson is stored. */
private const val LESSON_PROGRESS_DIRECTORY = "lesson-progress"
private const val JPEG_QUALITY = 88
private const val MAX_IMPORTED_TRANSLATION_BYTES = 8L * 1024 * 1024
private const val LAST_SHARED_VIDEO_PREFERENCE = "last_translation_share_video"

/** A transcript ready to copy or save as an .srt file named after the video, e.g. `Pancreas.srt`. */
internal data class SubtitleExport(
    val videoId: String,
    val title: String?,
    val cueCount: Int,
    val text: String,
) {
    val fileName: String get() = subtitleFileName(title, videoId)

    /** For copying and sharing: the video's title and link above the subtitles. */
    val textWithHeader: String get() = lessonHeader(title, videoId) + "\n" + text
}

internal fun preferredCaptionLanguages(sourcePreference: String): List<String> =
    sourcePreference.takeUnless { it == "auto" }?.let(::listOf).orEmpty()

internal fun resolvedSourcePreference(
    requested: String,
    resolved: String,
): String =
    requested.takeIf {
        it == "auto" || TranslationLanguages.normalize(it) == TranslationLanguages.normalize(resolved)
    } ?: "auto"

internal fun storedSourcePreference(raw: String?): String =
    raw
        ?.takeIf { it != "auto" }
        ?.let(::normalizeSupportedLanguage)
        ?: "auto"

private fun normalizeSupportedLanguage(code: String): String? {
    val normalized = TranslationLanguages.normalize(code)
    return normalized.takeIf(TranslationLanguages::isSupported)
}

internal fun normalizedOnboardingLanguages(
    nativeLanguage: String,
    learningLanguage: String,
): Pair<String, String>? {
    val native =
        TranslationLanguages.normalize(nativeLanguage).takeIf(TranslationLanguages::isSupported)
            ?: return null
    val learning =
        TranslationLanguages.normalize(learningLanguage).takeIf(TranslationLanguages::isSupported)
            ?: return null
    return native to learning
}

internal const val GUIDE_COMPLETED_PREFERENCE = "guide_completed"
internal const val DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE = "daily_goal_prompt_completed"
internal const val DAILY_GOAL_MINUTES_PREFERENCE = "daily_goal_minutes"
internal const val SPLIT_LONG_SENTENCES_PREFERENCE = "split_long_sentences"
internal const val LOCK_OVERLAY_TO_VIDEO_PREFERENCE = "lock_overlay_to_video_player"
internal const val PRELOAD_MODELS_ENABLED_PREFERENCE = "preload_translation_models"
internal const val NATURAL_SUBTITLES_PREFERENCE = "enhanced_natural_subtitles"
internal const val WORD_LEARNING_ENABLED_PREFERENCE = "word_learning_mode_enabled"
internal const val WORD_LEARNING_TARGET_PREFERENCE = "word_learning_target"
internal const val TAP_TO_LEARN_PREFERENCE = "tap_to_learn_enabled"
internal const val WORD_LEARNING_ACTIVE_ONLY_PREFERENCE = "word_learning_active_only"

/**
 * The "guide_completed" preference only exists after the first-launch guide has
 * been finished once, so a missing preference means: show the guide to
 * brand-new users while treating users who onboarded before the guide existed
 * as already having seen it.
 */
internal fun initialGuideCompleted(
    preferenceExists: Boolean,
    preferenceValue: Boolean,
    onboardingCompleted: Boolean,
): Boolean = if (preferenceExists) preferenceValue else onboardingCompleted

class AppViewModel internal constructor(
    application: Application,
    private val captionProvider: CaptionProvider,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        // Survives Android closing the app in the background, so returning skips the caption download.
        RecentCaptionTracks(YouTubeCaptionProvider(), File(application.cacheDir, "recent-caption-tracks")),
    )

    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val translator =
        OnDeviceTranslator(
            cacheDirectory = File(application.cacheDir, "subtitle-translations"),
            modelDirectory = File(application.filesDir, "translation-models"),
        )

    // Speaks translated sentences in the "uz" build; idle where no voice exists.
    private val dubbingVoice = createDubbingVoice(application)
    private val dubbing = DubbingController(viewModelScope, dubbingVoice)
    internal val vocabulary = VocabularyRepository.get(application)
    internal val immersion = ImmersionRepository.get(application)

    init {
        // Japanese word analysis downloads its dictionary here the first time Japanese is shown.
        JapaneseMorphology.useStore(JapaneseDictionaryStore(File(application.filesDir, "japanese-dictionary")))
        // The UZI glossary (small asset) for term hints when English captions are shown in Uzbek.
        com.kienhoang.dualsubreplay.data.UziGlossaryStore.load(application, viewModelScope)
        // Terms Claude suggested and the doctor approved, kept across lessons.
        viewModelScope.launch { com.kienhoang.dualsubreplay.data.LearnedGlossaryStore.load(application) }
        // The list of translated lessons and their notes.
        viewModelScope.launch { LessonLibraryStore.load(application) }
        viewModelScope.launch { ApiUsageLog.load(application) }
        viewModelScope.launch { AppProposalStore.load(application) }
        // Focus mode needs the catalog to tell lessons from other YouTube videos.
        if (shokalonFocusMode) viewModelScope.launch { UziCatalogStore.load(application) }
        // Speaking a translated lesson in advance uses the same voice as playback.
        LessonVoicePrefetch.voice = dubbingVoice
        // Playback speaks whole translated sentences, so the voice is prepared sentence by sentence too.
        LessonVoicePrefetch.currentLessonTexts = {
            importedTranslation
                ?.takeIf { it.first == _state.value.activeVideoId }
                ?.second
                ?.let { cues -> translatedSentenceSpans(cues).map { it.text } }
                .orEmpty()
        }
        VideoFrameSource.onCaptureNow = ::captureCurrentFrame
    }

    /** "Claude bilan tarjima": translates the open lesson with the doctor's own Claude API key. */
    private val claude =
        ClaudeLessonCoordinator(
            scope = viewModelScope,
            segmentsFor = { videoId -> lessonSegments(videoId).second },
            importSrt = { text, videoId, source -> importTranslation(text, videoId, source) },
            notify = { Toast.makeText(getApplication(), it, Toast.LENGTH_LONG).show() },
            progress = LessonProgressFiles(File(application.filesDir, LESSON_PROGRESS_DIRECTORY)),
        )
    internal val claudeRun: StateFlow<ClaudeRunState> get() = claude.state

    fun translateLessonWithClaude() {
        val videoId = _state.value.activeVideoId ?: return
        claude.start(videoId, LessonEngine.CLAUDE)
    }

    /** The whole open lesson in one Gemini request (the free tier counts requests, not sentences). */
    fun translateLessonWithGemini() {
        val videoId = _state.value.activeVideoId ?: return
        claude.start(videoId, LessonEngine.GEMINI)
    }

    fun cancelClaudeTranslation() = claude.cancel()

    /** "Konspekt va test": the open lesson's summary and quiz, stored per video. */
    private val lessonNotes =
        LessonNotesCoordinator(
            scope = viewModelScope,
            inputFor = ::notesInput,
            load = LessonLibraryStore::notes,
            save = LessonLibraryStore::saveNotes,
        )
    internal val lessonNotesState: StateFlow<LessonNotesState> get() = lessonNotes.state

    fun showLessonNotes() = lessonNotes.show(_state.value.activeVideoId)

    internal fun writeLessonNotes(engine: LessonEngine) {
        val videoId = _state.value.activeVideoId ?: return
        lessonNotes.start(videoId, engine)
    }

    fun cancelLessonNotes() = lessonNotes.cancel()

    /** What the notes of [videoId] are written from: its English subtitles and, when stored, its Uzbek translation. */
    private suspend fun notesInput(videoId: String): LessonNotesInput {
        val (title, segments) = lessonSegments(videoId)
        val uzbek =
            importedLines(videoId)
                ?: withContext(Dispatchers.IO) { runCatching { readImportedTranslation(videoId) }.getOrNull() }?.map { it.text }
        return LessonNotesInput(
            title ?: lessonRecord(videoId)?.title,
            segments.map { it.originalText },
            uzbek,
            segments.map { (it.startMs / MS_PER_SECOND).toInt() },
        )
    }

    /** "Konspektsiz darslarga yozish" of a SHOKALON section. */
    private val sectionNotes =
        SectionNotesBatch(viewModelScope, ::notesInput, LessonLibraryStore::notes, LessonLibraryStore::saveNotes)
    internal val sectionNotesProgress: StateFlow<SectionNotesProgress> get() = sectionNotes.progress

    internal fun writeSectionNotes(
        section: String,
        videos: List<CatalogVideo>,
        engine: LessonEngine,
    ) = sectionNotes.start(section, videos, engine)

    fun cancelSectionNotes() = sectionNotes.cancel()

    /** "SHOKALON chat": Claude with the doctor's key, reading and proposing through [chatAccess]. */
    internal val chatAgent by lazy { ShokalonChatAgent(viewModelScope, chatAccess) }

    private val chatAccess =
        object : ChatAppAccess {
            override fun context(): String {
                val videoId = _state.value.activeVideoId ?: return "No lesson is open (the doctor is on the sections screen)."
                val section = lessonSteps(UziCatalogStore.sections.value, videoId)?.section
                val translated = storedTranslationCues()?.size
                return listOfNotNull(
                    "Open lesson: ${activeLessonTitle() ?: videoId} (YouTube $videoId)",
                    section?.let { "Section: $it" },
                    if (translated != null) "Stored Uzbek translation: $translated cues" else "No stored Uzbek translation yet",
                    "Notes written: ${if (videoId in LessonLibraryStore.notedIds.value) "yes" else "no"}",
                    "Sentences marked for checking: ${reviewMarkStore.value[videoId].orEmpty().size}",
                ).joinToString("\n")
            }

            override suspend fun lessonSentences(): Triple<String, String?, List<ReviewSentence>> {
                val videoId = _state.value.activeVideoId ?: throw IllegalStateException("No lesson is open.")
                val (title, sentences) = lessonReviewSentences(onlyMarked = false)
                return Triple(videoId, title, sentences)
            }

            override suspend fun lessonNotes(): LessonNotes? = _state.value.activeVideoId?.let { LessonLibraryStore.notes(it) }

            override fun catalog(): String = chatCatalog(UziCatalogStore.sections.value.orEmpty())

            override suspend fun previewReplace(request: ReplaceRequest): String = replaceInTranslations(request, apply = false)

            override suspend fun applyFixes(
                videoId: String,
                fixes: List<SentenceFix>,
            ): String = applySentenceFixes(videoId, fixes)

            override suspend fun applyReplace(request: ReplaceRequest): String = replaceInTranslations(request, apply = true)

            override fun rewriteNotes(): String {
                check(_state.value.activeVideoId != null) { NO_VIDEO_MESSAGE }
                writeLessonNotes(LessonEngine.CLAUDE)
                return "Konspekt va test Claude bilan qayta yozilmoqda (“Konspekt va test” kartasida ko‘rinadi)."
            }

            override suspend fun recordProposal(proposal: AppProposal) = AppProposalStore.add(proposal)

            override fun backend(): ClaudeBackend = ClaudeBackend(DubbingSettings.claudeApiKey.value, DubbingSettings.claudeModel.value)
        }

    /** The catalog for Claude: sections, topics and lectures, with T (translated) and K (notes) marks. */
    private fun chatCatalog(sections: List<CatalogSection>): String {
        val translated = LessonLibraryStore.records.value.mapTo(HashSet()) { it.videoId }
        val noted = LessonLibraryStore.notedIds.value
        return buildString {
            sections.forEach { section ->
                append("# ").append(section.name).append('\n')
                section.topics.forEach { topic ->
                    append("## ").append(topic.name).append('\n')
                    topic.videos.forEach { video ->
                        append("- ").append(video.title)
                        if (video.videoId in translated) append(" [T]")
                        if (video.videoId in noted) append(" [K]")
                        append('\n')
                    }
                }
            }
        }.ifEmpty { "The catalog is not loaded." }
    }

    private var frameJob: Job? = null

    /** Takes every planned picture of the open lesson's notes from the video, one after another. */
    fun captureNoteFrames() {
        val videoId = _state.value.activeVideoId ?: return
        if (frameJob?.isActive == true) return
        frameJob =
            viewModelScope.launch {
                val notes = LessonLibraryStore.notes(videoId)
                val todo = notes?.frames.orEmpty().filter { it.image.isEmpty() }
                if (notes == null || todo.isEmpty()) {
                    toast(if (notes == null) "Avval konspekt yozing." else "Barcha rasmlar olingan.")
                    return@launch
                }
                toast("Rasmlar olinmoqda: video ${todo.size} ta joyga o\u2018tib to\u2018xtaydi.")
                var current: LessonNotes = notes
                var taken = 0
                for (frame in todo) {
                    if (_state.value.activeVideoId != videoId) break
                    val (next, stop) = takeFrame(videoId, frame.seconds.toFloat()) { name -> current.withFrameImage(frame.seconds, name) }
                    if (next != null) {
                        current = next
                        taken++
                    }
                    if (stop) break
                }
                toast("Rasmli konspekt: ${todo.size} tadan $taken ta kadr olindi.")
            }
    }

    /** "📷": the frame on the screen now goes into the notes, with the current subtitle as its caption. */
    fun captureCurrentFrame() {
        val videoId = _state.value.activeVideoId ?: return
        if (frameJob?.isActive == true) return
        frameJob =
            viewModelScope.launch {
                val notes = LessonLibraryStore.notes(videoId)
                val segment = _state.value.segments.getOrNull(_state.value.currentIndex)
                val caption = segment?.translatedText?.takeIf(String::isNotBlank) ?: segment?.originalText.orEmpty()
                takeFrame(videoId, second = null) { name ->
                    val seconds = name.removeSuffix(".jpg").toIntOrNull() ?: 0
                    addManualFrame(notes, NoteFrame(seconds, caption.ifBlank { "Kadr ${formatVideoTime(seconds)}" }, name))
                }.first?.let { saved ->
                    toast("\uD83D\uDCF7 Kadr konspektga qo\u2018shildi (${saved.frames.size} ta rasm).")
                }
            }
    }

    /**
     * Takes one frame and stores it; returns the updated notes (null when nothing was saved) and
     * whether the remaining frames should not be tried (black or failed copy).
     */
    private suspend fun takeFrame(
        videoId: String,
        second: Float?,
        update: (String) -> LessonNotes,
    ): Pair<LessonNotes?, Boolean> {
        val grab = VideoFrameSource.grab
        if (grab == null) {
            toast("Avval dars videosini oching.")
            return null to true
        }
        return when (val result = grab(second)) {
            is FrameGrab.Taken -> {
                val jpeg = withContext(Dispatchers.Default) { jpegOf(result.bitmap) }
                val name = LessonLibraryStore.saveFrame(videoId, result.seconds, jpeg) ?: return null to false
                val next = update(name)
                LessonLibraryStore.saveNotes(videoId, next)
                lessonNotes.replace(videoId, next)
                next to false
            }
            FrameGrab.Black -> {
                toast("Kadr qora chiqdi: bu telefonda video tasvirini dastur ichidan nusxalab bo\u2018lmadi.")
                null to true
            }
            is FrameGrab.Failed -> {
                toast(result.reason)
                null to true
            }
        }
    }

    private fun jpegOf(bitmap: android.graphics.Bitmap): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }

    private fun toast(message: String) = Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()

    /** The last lesson page, where focus mode returns from a page that is not a lesson. */
    private var lastLessonUrl: String? = null

    /** A catalog lecture, a translated lesson or a video the doctor opened himself; unknown until the catalog loads. */
    internal fun isLessonVideo(videoId: String): Boolean =
        videoId in focusRequestedVideos ||
            LessonLibraryStore.records.value.any { it.videoId == videoId } ||
            catalogHasVideo(com.kienhoang.dualsubreplay.data.UziCatalogStore.sections.value, videoId) != false

    /** Focus mode: leaves another video, Shorts or a feed for the last lesson (or the empty home page). */
    private fun leaveDistraction() {
        val back = lastLessonUrl ?: YOUTUBE_HOME_URL
        _state.update { it.copy(browserUrl = back, browserNavigationRequestId = it.browserNavigationRequestId + 1) }
        toast(FOCUS_BLOCKED_MESSAGE)
    }

    /** Opens a lesson from the catalog or reopens one from the list of translated lessons. */
    fun openTranslatedLesson(videoId: String) {
        openLessonLink(mobileWatchUrl(videoId))
    }

    /** Fetches a caption track and remembers the video's title for the list of translated lessons. */
    private suspend fun fetchCaptionTrack(
        videoId: String,
        languages: List<String>,
    ): CaptionTrackResult {
        // A recording the user chose to use stands in for the track YouTube does not let the app download.
        captionRecorder.store.completeCues(videoId)?.let { (language, cues) ->
            return CaptionTrackResult(
                languageCode = TranslationLanguages.normalize(language ?: "en"),
                isGenerated = true,
                cues = cues,
                title = lessonTitles[videoId],
            )
        }
        val track = captionProvider.fetch(videoId, languages)
        track.title?.takeIf(String::isNotBlank)?.let { lessonTitles[videoId] = it }
        return track
    }

    /** The stored translation's lines of [videoId] when that lesson is open, else null. */
    private fun importedLines(videoId: String?): List<String>? {
        val (id, cues) = importedTranslation ?: return null
        return if (id == videoId) cues.map { it.text } else null
    }

    /** Video titles seen in this session, for the list of translated lessons. */
    private val lessonTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val immersionTracker = ImmersionTimeTracker()
    private val immersionAccumulator = ImmersionAccumulator()
    private var loadingJob: Job? = null
    private var translationWarmupJob: Job? = null
    private var loadGeneration = 0L
    private var latestPlaybackSecondMs = 0L
    private val subtitleDirectory by lazy {
        File(application.cacheDir, "subtitle-transcripts").apply {
            // Cached transcripts belong to this ViewModel; discard leftovers after process death.
            deleteRecursively()
            mkdirs()
        }
    }
    private val playbackRequests = MutableStateFlow(CaptionPlaybackRequest())
    private var playbackKnown = false
    private var appVisible = true
    private val liveCaptionTracker = LiveCaptionTracker()
    private val captionHighlightResolver = CaptionHighlightResolver()
    private var liveCaptionProgress: LiveCaptionProgress? = null
    private var playbackSessionId: String? = null
    private val liveTranslationGate = LiveTranslationGate()
    private var liveTranslationJob: Job? = null
    private var rejectedLiveRevision: Long? = null

    /** The open video's imported translation, keyed by video id so a late load cannot mix videos. */
    private var importedTranslation: Pair<String, List<RawCaptionCue>>? = null
    private val importedTranslationDirectory = File(application.filesDir, "imported-translations")

    /** Records the captions the page shows for videos whose caption track cannot be downloaded. */
    private val captionRecorder =
        CaptionRecorder(RecordedCaptionStore(File(application.filesDir, RecordedCaptionStore.FOLDER)), viewModelScope) { id, summary ->
            _state.update { if (it.activeVideoId == id) it.copy(recordedCaptions = summary) else it }
        }

    private val _state =
        MutableStateFlow(
            DualSubUiState(
                browserUrl =
                    preferences
                        .getString("last_browser_url", YOUTUBE_HOME_URL)
                        // SHOKALON starts on its sections screen: no lesson plays until the doctor picks one.
                        ?.takeUnless { shokalonFocusMode }
                        ?.let(::trustedEmbeddedUrlOrHome)
                        ?: YOUTUBE_HOME_URL,
                originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
                translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
                fontScale = preferences.getFloat("font_scale", 1f),
                portraitPanelOffsetFraction =
                    normalizePortraitPanelOffsetFraction(
                        preferences.getFloat(
                            PORTRAIT_PANEL_OFFSET_PREFERENCE,
                            DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                        ),
                    ),
                sourcePreference =
                    storedSourcePreference(
                        preferences.getString("preferred_caption_language", "auto"),
                    ),
                targetLanguage =
                    preferences
                        .getString("target_language", "vi")
                        ?.takeIf(TranslationLanguages::isSupported)
                        ?: "vi",
                onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                guideCompleted =
                    initialGuideCompleted(
                        preferenceExists = preferences.contains(GUIDE_COMPLETED_PREFERENCE),
                        preferenceValue = preferences.getBoolean(GUIDE_COMPLETED_PREFERENCE, false),
                        onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                    ),
                dailyGoalPromptCompleted =
                    initialDailyGoalPromptCompleted(
                        preferenceExists = preferences.contains(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE),
                        preferenceValue = preferences.getBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, false),
                        guideCompleted =
                            initialGuideCompleted(
                                preferenceExists = preferences.contains(GUIDE_COMPLETED_PREFERENCE),
                                preferenceValue = preferences.getBoolean(GUIDE_COMPLETED_PREFERENCE, false),
                                onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                            ),
                    ),
                dailyGoalMinutes = storedDailyGoalMinutes(preferences.getInt(DAILY_GOAL_MINUTES_PREFERENCE, 0)),
                landscapeSplitEnabled = preferences.getBoolean("landscape_split_enabled", true),
                originalColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_ORIGINAL_COLOR_PREFERENCE, null),
                        DEFAULT_ORIGINAL_COLOR_KEY,
                    ),
                translatedColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_TRANSLATED_COLOR_PREFERENCE, null),
                        DEFAULT_TRANSLATED_COLOR_KEY,
                    ),
                highlightColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE, null),
                        DEFAULT_HIGHLIGHT_COLOR_KEY,
                    ),
                wordHighlightEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, true),
                    ),
                customColorsEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, true),
                    ),
                captionFormat =
                    storedCaptionFormat(
                        preferences.getString(CAPTION_FORMAT_PREFERENCE, null),
                        preferences.getBoolean(SPLIT_LONG_SENTENCES_PREFERENCE, true),
                        BUILD_PREFERS_WHOLE_SENTENCE,
                    ),
                lockOverlayToVideo =
                    storedFeatureEnabled(
                        preferences.getBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, false),
                    ),
                preloadModelsEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true),
                    ),
                naturalSubtitlesEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(NATURAL_SUBTITLES_PREFERENCE, true),
                    ),
                wordLearningEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_LEARNING_ENABLED_PREFERENCE, true),
                    ),
                wordLearningTarget =
                    preferences.getString(
                        WORD_LEARNING_TARGET_PREFERENCE,
                        "both",
                    ) ?: "both",
                wordLearningActiveOnly =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, true),
                    ),
                autoPronounce = preferences.getBoolean("auto_pronounce", true),
                tapToLearnEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(TAP_TO_LEARN_PREFERENCE, true),
                    ),
            ),
        )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    private val visibilityListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ORIGINAL_VISIBILITY || key == TRANSLATED_VISIBILITY || key == null) {
                _state.update {
                    it.copy(
                        originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
                        translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
                    )
                }
            }
        }

    override fun onCleared() {
        captionRecorder.saveNow()
        flushImmersion()
        loadingJob?.cancel()
        dubbing.reset()
        preferences.unregisterOnSharedPreferenceChangeListener(visibilityListener)
        super.onCleared()
    }

    internal fun onWebPlaybackPaused(
        videoId: String,
        paused: Boolean,
    ) {
        if (_state.value.activeVideoId != videoId) return
        if (paused && !_state.value.playbackPaused) flushImmersion()
        _state.update { it.copy(playbackPaused = paused) }
        updatePlaybackRequest()
    }

    internal fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) {
            immersionTracker.reset()
            flushImmersion()
            dubbing.reset()
        }
        updatePlaybackRequest()
    }

    /** Credits watched time to the language being learned; see [ImmersionTimeTracker]. */
    private fun trackImmersion(
        current: DualSubUiState,
        videoId: String,
        timeMs: Long,
        seek: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val language = immersionLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        val playing = appVisible && !current.playbackPaused && !seek && language != null
        val credited = immersionTracker.onTick(now, timeMs, playing)
        if (language != null && credited > 0) immersionAccumulator.add(LocalDate.now(), language, videoId, credited)
        if (immersionAccumulator.shouldFlush(now)) flushImmersion()
    }

    private fun flushImmersion() {
        immersion.record(immersionAccumulator.drain(SystemClock.elapsedRealtime()))
    }

    private fun updatePlaybackRequest(seek: Boolean = false) {
        playbackRequests.update {
            CaptionPlaybackRequest(
                // Translation scheduling needs seconds, while karaoke keeps its 33 ms clock.
                timeMs =
                    if (seek || !it.enabled || latestPlaybackSecondMs / 1000 != it.timeMs / 1000) {
                        latestPlaybackSecondMs
                    } else {
                        it.timeMs
                    },
                paused = _state.value.playbackPaused,
                enabled = appVisible && playbackKnown && _state.value.activeVideoId != null,
                seekGeneration = it.seekGeneration + if (seek) 1 else 0,
                translationAttempt = it.translationAttempt,
            )
        }
    }

    init {
        preferences.edit().remove(KARAOKE_TIMING_MODE_PREFERENCE).apply()
        preferences.registerOnSharedPreferenceChangeListener(visibilityListener)
        viewModelScope.launch {
            try {
                vocabulary.refresh()
                retireLegacyDownloadJobs(application)
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                // The saved-words screen reports storage failures with a retry on reopen.
            }
        }
        if (_state.value.preloadModelsEnabled) {
            val source = _state.value.sourcePreference.takeUnless { it == "auto" } ?: "en"
            warmTranslationModel(sourceLanguage = source, targetLanguage = _state.value.targetLanguage)
        }
    }

    fun acceptSharedText(text: String) {
        // A translation shared back from an AI chat is subtitles, even if it mentions a link.
        if (parseSubtitleFile(text).isNotEmpty()) {
            importSharedTranslation(text)
            return
        }
        val videoId = YouTubeUrlParser.extractVideoId(text) ?: return
        focusRequestedVideos += videoId
        val watchUrl = mobileWatchUrl(videoId)
        preferences.edit().putString("last_browser_url", watchUrl).apply()
        _state.update {
            it.copy(
                browserUrl = watchUrl,
                browserNavigationRequestId = it.browserNavigationRequestId + 1,
            )
        }
        openVideo(videoId)
    }

    /** Opens a lesson link typed or pasted by the user. Returns false when it is not a YouTube video link. */
    fun openLessonLink(text: String): Boolean {
        if (YouTubeUrlParser.extractVideoId(text) == null) return false
        acceptSharedText(text)
        return true
    }

    /**
     * The open video's full transcript as SubRip text, so it can be copied or saved, translated
     * outside the app, and imported back with [importTranslation].
     */
    internal suspend fun subtitleExport(): SubtitleExport {
        val current = _state.value
        val videoId = current.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val (title, segments) = lessonSegments(videoId)
        return SubtitleExport(
            videoId = videoId,
            title = title,
            cueCount = segments.size,
            text = withContext(Dispatchers.Default) { formatSrt(segments) },
        )
    }

    /** A lesson's title and its sentences as the app shows them (the SubtitleMerger rows), for export and Claude. */
    private suspend fun lessonSegments(videoId: String): Pair<String?, List<SubtitleSegment>> {
        val current = _state.value
        val track =
            withContext(Dispatchers.IO) {
                fetchCaptionTrack(videoId, preferredCaptionLanguages(current.sourcePreference))
            }
        val segments =
            withContext(Dispatchers.Default) {
                SubtitleMerger.merge(track.cues, enhancedNaturalFlow = current.naturalSubtitlesEnabled)
            }
        if (segments.isEmpty()) throw IllegalStateException("Bu videoning subtitrida matn yo\u2018q.")
        return track.title to segments
    }

    /** The open lesson's title, for files the doctor sends out. */
    internal fun activeLessonTitle(): String? = _state.value.activeVideoId?.let { lessonTitles[it] ?: lessonRecord(it)?.title }

    private fun lessonRecord(videoId: String) = LessonLibraryStore.records.value.firstOrNull { it.videoId == videoId }

    /** Sentences the doctor marked for checking, per lesson: their start and end times. */
    private val reviewMarkStore = sentenceReviewMarks

    /** Marks (or unmarks) a sentence of the open lesson for checking; true when it is marked now. */
    internal fun toggleReviewMark(
        startMs: Long,
        endMs: Long,
    ): Boolean {
        val videoId = _state.value.activeVideoId ?: return false
        val mark = startMs to endMs
        val current = reviewMarkStore.value[videoId].orEmpty()
        val next = if (mark in current) current - mark else current + mark
        reviewMarkStore.value = reviewMarkStore.value + (videoId to next)
        preferences.edit().putString(REVIEW_MARKS_PREFIX + videoId, next.joinToString(",") { "${it.first}-${it.second}" }).apply()
        return mark in next
    }

    private fun loadReviewMarks(videoId: String) {
        if (videoId in reviewMarkStore.value) return
        val saved =
            preferences
                .getString(REVIEW_MARKS_PREFIX + videoId, null)
                .orEmpty()
                .split(',')
                .mapNotNull(::parseReviewMark)
                .toSet()
        reviewMarkStore.value = reviewMarkStore.value + (videoId to saved)
    }

    private fun parseReviewMark(part: String): Pair<Long, Long>? {
        val bounds = part.split('-')
        if (bounds.size != 2) return null
        val start = bounds[0].toLongOrNull() ?: return null
        val end = bounds[1].toLongOrNull() ?: return null
        return start to end
    }

    /** Replaces the stored translation of one sentence (from [startMs] to [endMs]) with the doctor's text. */
    internal suspend fun saveSentenceCorrection(
        startMs: Long,
        endMs: Long,
        text: String,
    ): String {
        val cues = storedTranslationCues()
        if (cues == null) {
            // Claude is still translating: the correction waits and is applied to the finished translation.
            val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
            val pending = pendingCorrections(videoId).filterNot { it.startMs == startMs && it.endMs == endMs }
            savePendingCorrections(videoId, pending + ReviewSentence(startMs, endMs, "", text.trim()))
            return "Tarjima hali tugamagan: tuzatish eslab qolindi va tarjima tugashi bilan shu gapga qo\u2018llanadi."
        }
        saveEditedTranslation(applySentenceCorrection(cues, startMs, endMs, text))
        return if (text.isBlank()) "Gap o\u2018chirildi." else "Gap tuzatildi va saqlandi."
    }

    /** The marked sentences of the open lesson as one file to check elsewhere (all sentences when none is marked). */
    internal suspend fun reviewFileExport(): SubtitleExport {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val (title, sentences) = lessonReviewSentences(onlyMarked = true)
        return SubtitleExport(videoId, "${title ?: videoId} (tekshirish)", sentences.size, reviewFileText(title, videoId, sentences))
    }

    /**
     * The open lesson's sentences with their stored Uzbek: the marked ones when [onlyMarked] and some are
     * marked, else all of them.
     */
    internal suspend fun lessonReviewSentences(onlyMarked: Boolean): Pair<String?, List<ReviewSentence>> {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val cues = storedTranslationCues() ?: throw IllegalStateException(NO_STORED_TRANSLATION_MESSAGE)
        val (title, segments) = lessonSegments(videoId)
        val rows =
            withContext(Dispatchers.Default) {
                applyImportedTranslation(importedSentenceRows(segments, translatedSentenceSpans(cues)), cues)
            }
        val marks = if (onlyMarked) reviewMarkStore.value[videoId].orEmpty() else emptySet()
        val chosen = rows.filter { row -> marks.isEmpty() || (row.startMs to row.endMs) in marks }
        val sentences = chosen.map { ReviewSentence(it.startMs, it.endMs, it.originalText, it.translatedText.orEmpty()) }
        return (title ?: activeLessonTitle()) to sentences
    }

    /** Claude checks [sentences] of the open lesson against the glossary; nothing is changed yet. */
    internal suspend fun fixSentencesWithClaude(
        sentences: List<ReviewSentence>,
        request: String? = null,
    ): List<SentenceFix> {
        val backend = ClaudeBackend(DubbingSettings.claudeApiKey.value, DubbingSettings.claudeModel.value)
        // A few sentences get the whole reviewed glossary: their misheard English ("anooic") may hide the term.
        val glossary =
            if (sentences.size <= FULL_GLOSSARY_SENTENCES) {
                UziGlossaryStore.glossary.value?.entries.orEmpty()
            } else {
                glossaryForBatch(UziGlossaryStore.glossary.value, sentences.map { it.english })
            }
        return requestSentenceFixes(backend, activeLessonTitle(), sentences, glossary, request)
    }

    /** Claude's check of one sentence (the long-pressed one). */
    internal suspend fun claudeFixOne(
        sentence: ReviewSentence,
        meaning: String?,
    ): SentenceFix = fixSentencesWithClaude(listOf(sentence), meaning?.let(::meaningRequest)).first()

    /** "Claude bilan tuzatish" of the marked sentences (all when none is marked). */
    internal val claudeFixActions =
        ClaudeFixActions(
            check = {
                val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
                videoId to fixSentencesWithClaude(lessonReviewSentences(onlyMarked = true).second)
            },
            apply = ::applySentenceFixes,
            rewriteNotes = { writeLessonNotes(LessonEngine.CLAUDE) },
        )

    /** Applies the chosen fixes to the open lesson [videoId] and takes them off the check list. */
    internal suspend fun applySentenceFixes(
        videoId: String,
        fixes: List<SentenceFix>,
    ): String {
        check(_state.value.activeVideoId == videoId) { "Bu tuzatishlar boshqa darsga tegishli: o‘sha darsni oching." }
        require(fixes.isNotEmpty()) { "Qo‘llash uchun tuzatish tanlanmagan." }
        return applyCorrections(videoId, fixes.map { it.sentence.copy(uzbek = it.uzbek) })
    }

    private suspend fun applyCorrections(
        videoId: String,
        corrections: List<ReviewSentence>,
    ): String {
        var cues = storedTranslationCues() ?: throw IllegalStateException(NO_STORED_TRANSLATION_MESSAGE)
        corrections.forEach { cues = applySentenceCorrection(cues, it.startMs, it.endMs, it.uzbek) }
        saveEditedTranslation(cues)
        val done = corrections.mapTo(HashSet()) { it.startMs to it.endMs }
        val left = reviewMarkStore.value[videoId].orEmpty() - done
        reviewMarkStore.value = reviewMarkStore.value + (videoId to left)
        preferences.edit().putString(REVIEW_MARKS_PREFIX + videoId, left.joinToString(",") { "${it.first}-${it.second}" }).apply()
        return "${corrections.size} ta gap tuzatildi va saqlandi."
    }

    /** Applies a checked review file (or text) to the open lesson; returns what was done. */
    internal suspend fun importReviewFile(text: String): String {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        reviewFileVideoId(text)?.let { require(it == videoId) { "Bu fayl boshqa darsga tegishli." } }
        val corrections = parseReviewFile(text)
        require(corrections.isNotEmpty()) { "Faylda tuzatilgan gap topilmadi (\u201C[1] 00:00:01,000 --> \u2026\u201D va \u201CUZ:\u201D qatorlari kerak)." }
        return applyCorrections(videoId, corrections)
    }

    /** The translations as they were before the last "Hammasini almashtirish", for "Bekor qilish". */
    private var replaceBackup: Map<String, List<RawCaptionCue>> = emptyMap()

    /**
     * Finds [request].find (a word or a phrase) in the stored translations of its scope: one sentence, the open
     * lesson, the open lesson's catalog section, or every stored lesson. Without [apply] only reports how many
     * places and shows a few; with it replaces them and keeps the old text for [undoReplace].
     */
    internal suspend fun replaceInTranslations(
        request: ReplaceRequest,
        apply: Boolean,
    ): String {
        val activeId = _state.value.activeVideoId
        val ids = replaceScopeIds(request.scope, activeId)
        require(ids.isNotEmpty()) { NO_STORED_TRANSLATION_MESSAGE }
        var places = 0
        val examples = mutableListOf<String>()
        val backup = mutableMapOf<String, List<RawCaptionCue>>()
        for (videoId in ids) {
            val cues =
                (if (videoId == activeId) storedTranslationCues() else withContext(Dispatchers.IO) { readImportedTranslation(videoId) })
                    ?: continue
            val result = replaceWithin(cues, request)
            if (result.count == 0) continue
            places += result.count
            if (examples.size < MAX_REPLACE_EXAMPLES) examples += result.examples.take(MAX_REPLACE_EXAMPLES - examples.size)
            if (apply) {
                backup[videoId] = cues
                storeTranslation(videoId, result.cues, videoId == activeId)
            }
        }
        if (places == 0) return "\u201C${request.find.trim()}\u201D topilmadi."
        if (!apply) return "$places joyda topildi:\n" + examples.joinToString("\n")
        replaceBackup = backup
        return "$places joyda almashtirildi (${backup.size} ta dars). Kerak bo\u2018lsa \u201CBekor qilish\u201D ni bosing."
    }

    /** The lessons a replacement looks at: the open one, its section's stored ones, or all stored ones. */
    private suspend fun replaceScopeIds(
        scope: ReplaceScope,
        activeId: String?,
    ): List<String> {
        val stored =
            withContext(Dispatchers.IO) {
                importedTranslationDirectory.listFiles { file -> file.extension == "srt" }.orEmpty().map { it.nameWithoutExtension }
            }
        return when (scope) {
            ReplaceScope.SENTENCE, ReplaceScope.LESSON -> {
                check(activeId == null || storedTranslationCues() != null) { REPLACE_WAIT_MESSAGE }
                listOfNotNull(activeId)
            }
            ReplaceScope.ALL -> stored
            ReplaceScope.SECTION -> {
                val section =
                    UziCatalogStore.sections.value?.firstOrNull { section ->
                        section.topics.any { topic -> topic.videos.any { it.videoId == activeId } }
                    } ?: throw IllegalStateException("Bu dars mundarijadagi bo\u2018limga kirmaydi.")
                val inSection = section.topics.flatMap { topic -> topic.videos.map { it.videoId } }.toSet()
                stored.filter { it in inSection }
            }
        }
    }

    /** The replacement inside one lesson; for one sentence only the cues of that sentence are touched. */
    private fun replaceWithin(
        cues: List<RawCaptionCue>,
        request: ReplaceRequest,
    ): CueReplacement {
        if (request.scope != ReplaceScope.SENTENCE) return replaceInCues(cues, request.find, request.replacement, request.wholeWord)
        // Sentence rows are built from cues cut at sentence ends, so the sentence is looked for among the same pieces.
        val pieces = splitCuesAtSentenceEnds(cues)
        val inside = pieces.indices.filter { (pieces[it].startMs + pieces[it].endMs) / 2 in request.sentenceStartMs until request.sentenceEndMs }
        val result = replaceInCues(inside.map(pieces::get), request.find, request.replacement, request.wholeWord)
        val changed = pieces.toMutableList()
        inside.forEachIndexed { position, index -> changed[index] = result.cues[position] }
        return result.copy(cues = changed)
    }

    /** Puts back the translations changed by the last "Hammasini almashtirish". */
    internal suspend fun undoReplace(): String {
        val backup = replaceBackup.ifEmpty { throw IllegalStateException("Bekor qilinadigan almashtirish yo\u2018q.") }
        val activeId = _state.value.activeVideoId
        backup.forEach { (videoId, cues) -> storeTranslation(videoId, cues, videoId == activeId) }
        replaceBackup = emptyMap()
        return "Oxirgi almashtirish bekor qilindi (${backup.size} ta dars)."
    }

    private suspend fun storeTranslation(
        videoId: String,
        cues: List<RawCaptionCue>,
        active: Boolean,
    ) {
        if (active) {
            saveEditedTranslation(cues)
        } else {
            withContext(Dispatchers.IO) {
                importedTranslationFile(videoId).writeText(formatSrt(cues.filter { it.text.isNotBlank() }.toSegments()), Charsets.UTF_8)
            }
        }
    }

    /** The open lesson's stored translation, cue by cue, for the editor; null when it has none. */
    internal fun storedTranslationCues(): List<RawCaptionCue>? =
        importedTranslation?.takeIf { it.first == _state.value.activeVideoId }?.second

    /** Stores the doctor's corrections of the open lesson's translation and shows them at once. */
    internal suspend fun saveEditedTranslation(cues: List<RawCaptionCue>): Int {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val engine = lessonRecord(videoId)?.engine ?: "Fayl"
        val kept = cues.filter { it.text.isNotBlank() }
        require(kept.isNotEmpty()) { "Tarjima bo\u2018sh bo\u2018lib qoldi." }
        return importTranslation(formatSrt(kept.toSegments()), videoId, source = engine)
    }

    /** The open lesson's translation as an .srt file, to copy or check in another app. */
    internal suspend fun storedTranslationExport(): SubtitleExport {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val cues = storedTranslationCues() ?: throw IllegalStateException(NO_STORED_TRANSLATION_MESSAGE)
        val title = lessonTitles[videoId] ?: lessonRecord(videoId)?.title
        val text = withContext(Dispatchers.Default) { formatSrt(cues.toSegments()) }
        return SubtitleExport(videoId, title?.let { "$it (UZ)" }, cues.size, text)
    }

    /**
     * The open lesson sentence by sentence, English above Uzbek with the time, for checking the translation
     * in another app (or by another AI) without the SRT numbers in the way.
     */
    internal suspend fun bilingualLessonExport(): SubtitleExport {
        val videoId = _state.value.activeVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val cues = storedTranslationCues() ?: throw IllegalStateException(NO_STORED_TRANSLATION_MESSAGE)
        val (title, segments) = lessonSegments(videoId)
        val rows =
            withContext(Dispatchers.Default) {
                applyImportedTranslation(importedSentenceRows(segments, translatedSentenceSpans(cues)), cues)
            }
        val text =
            buildString {
                rows.forEachIndexed { index, row ->
                    val seconds = row.startMs / 1_000
                    append("${index + 1}. [${seconds / 60}:${"%02d".format(seconds % 60)}]\n")
                    append("EN: ${row.originalText}\n")
                    append("UZ: ${row.translatedText.orEmpty()}\n\n")
                }
            }
        return SubtitleExport(videoId, title?.let { "$it (EN-UZ)" }, rows.size, text)
    }

    /**
     * The open video's transcript wrapped in translation instructions, to share to Claude or
     * ChatGPT. The video is remembered so the reply shared back lands on the same lesson.
     */
    internal suspend fun translationShare(): SubtitleExport {
        val export = subtitleExport()
        val target = _state.value.targetLanguage
        val language =
            if (TranslationLanguages.normalize(target) == "uz") {
                "o\u2018zbek (lotin yozuvida)"
            } else {
                TranslationLanguages.displayName(target)
            }
        preferences.edit().putString(LAST_SHARED_VIDEO_PREFERENCE, export.videoId).apply()
        val request = withContext(Dispatchers.Default) { translationRequest(export.text, language) }
        return export.copy(text = lessonHeader(export.title, export.videoId) + "\n" + request)
    }

    /**
     * Which lesson an imported translation belongs to: the YouTube link inside it (the shared text
     * starts with one), then the lesson last shared for translation, then the open video.
     */
    private fun importTargetFor(
        text: String,
        fallback: String?,
    ): String? =
        YouTubeUrlParser.extractVideoId(text.take(YouTubeUrlParser.MAX_SHARED_TEXT_LENGTH))
            ?: fallback

    /**
     * Uses SRT/VTT text as a video's translation instead of machine translation. A part of a lecture
     * is merged with what was imported before, so long lectures can be imported piece by piece. The
     * result is kept per video, so reopening the lesson shows it again. Returns the stored cue count.
     */
    private fun pendingCorrections(videoId: String): List<ReviewSentence> =
        pendingCorrectionsFromJson(preferences.getString(PENDING_FIX_PREFIX + videoId, null).orEmpty())

    private fun savePendingCorrections(
        videoId: String,
        pending: List<ReviewSentence>,
    ) {
        preferences
            .edit()
            .apply { if (pending.isEmpty()) remove(PENDING_FIX_PREFIX + videoId) else putString(PENDING_FIX_PREFIX + videoId, pendingCorrectionsToJson(pending)) }
            .apply()
    }

    /** Corrections made while the lesson was still being translated, put on its stored translation once. */
    private fun applyPendingCorrections(
        videoId: String,
        cues: List<RawCaptionCue>,
    ): List<RawCaptionCue> {
        val pending = pendingCorrections(videoId)
        if (pending.isEmpty()) return cues
        savePendingCorrections(videoId, emptyList())
        return pending.fold(cues) { current, fix -> applySentenceCorrection(current, fix.startMs, fix.endMs, fix.uzbek) }
    }

    internal suspend fun importTranslation(
        text: String,
        targetVideoId: String? = importTargetFor(text, _state.value.activeVideoId),
        source: String = "Fayl",
    ): Int {
        val videoId = targetVideoId ?: throw IllegalStateException(NO_VIDEO_MESSAGE)
        val incoming = withContext(Dispatchers.Default) { parseSubtitleFile(text) }
        if (incoming.isEmpty()) {
            throw IllegalArgumentException("Faylda subtitr topilmadi. SRT yoki VTT fayl tanlang.")
        }
        val cues =
            withContext(Dispatchers.IO) {
                check(importedTranslationDirectory.isDirectory || importedTranslationDirectory.mkdirs()) {
                    "Tarjimani saqlab bo\u2018lmadi."
                }
                val merged = applyPendingCorrections(videoId, mergeImportedCues(readImportedTranslation(videoId).orEmpty(), incoming))
                importedTranslationFile(videoId).writeText(formatSrt(merged.toSegments()), Charsets.UTF_8)
                merged
            }
        LessonLibraryStore.record(
            LessonRecord(videoId, lessonTitles[videoId].orEmpty(), source, System.currentTimeMillis(), cues.size),
        )
        if (_state.value.activeVideoId != videoId) return cues.size
        importedTranslation = videoId to cues
        loadVideo(videoId, showPanel = true)
        return cues.size
    }

    /**
     * A ZIP of finished SHOKALON translations (SHK_…_V###_UZ-LATIN.srt): each file is stored for its
     * catalog lecture like a translation imported by hand. Returns the Uzbek summary for the card.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun importTranslationZip(uri: android.net.Uri): String {
        val app = getApplication<Application>()
        val contents =
            withContext(Dispatchers.IO) {
                val ids = app.assets.open(SHOKALON_IDS_ASSET).use { parseShokalonIds(it.readBytes().toString(Charsets.UTF_8)) }
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Faylni ochib bo‘lmadi.")
                input.use { readTranslationZip(it, ids) }
            }
        var cues = 0
        val failed = mutableListOf<String>()
        contents.translations.forEach { translation ->
            try {
                cues += importTranslation(translation.text, translation.videoId, source = SHOKALON_TRANSLATION_SOURCE)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failed += "${translation.fileName} (${error.message})"
            }
        }
        return translationZipSummary(contents.translations.size - failed.size, cues, contents.unknown, failed)
    }

    /** An .srt/.vtt file shared into the app (for example from a file manager or an AI chat). */
    fun acceptSharedSubtitleFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val text =
                try {
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readBoundedText)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    null
                }
            if (text == null) {
                Toast.makeText(getApplication(), "Faylni ochib bo\u2018lmadi.", Toast.LENGTH_LONG).show()
            } else {
                importSharedTranslation(text)
            }
        }
    }

    /** A translation shared into the app goes to the lesson it was requested for. */
    private fun importSharedTranslation(text: String) {
        val target =
            importTargetFor(text, preferences.getString(LAST_SHARED_VIDEO_PREFERENCE, null) ?: _state.value.activeVideoId)
        viewModelScope.launch {
            val message =
                try {
                    val count = importTranslation(text, target)
                    if (target == _state.value.activeVideoId) {
                        "Tarjima yuklandi: $count qator."
                    } else {
                        "Tarjima saqlandi ($count qator). Dars ochilganda ko\u2018rinadi."
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    error.message ?: "Tarjimani yuklab bo\u2018lmadi."
                }
            Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        }
    }

    /** Removes the open video's imported translation and returns to machine translation. */
    fun clearImportedTranslation() {
        val videoId = _state.value.activeVideoId ?: return
        importedTranslation = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importedTranslationFile(videoId).delete() }
            if (_state.value.activeVideoId == videoId) loadVideo(videoId, showPanel = true)
        }
    }

    private fun importedTranslationFile(videoId: String): File = File(importedTranslationDirectory, "$videoId.srt")

    /** Loads the video's stored translation, if any. Returns false when a newer load replaced this one. */
    private suspend fun restoreImportedTranslation(
        videoId: String,
        generation: Long,
    ): Boolean {
        val imported = withContext(Dispatchers.IO) { runCatching { readImportedTranslation(videoId) }.getOrNull() }
        if (!isCurrentLoad(_state.value, videoId, generation)) return false
        importedTranslation = imported?.let { videoId to it }
        _state.update { it.copy(importedTranslationCues = imported?.size ?: 0) }
        return true
    }

    private fun readImportedTranslation(videoId: String): List<RawCaptionCue>? {
        val file = importedTranslationFile(videoId)
        if (!file.isFile || file.length() > MAX_IMPORTED_TRANSLATION_BYTES) return null
        return parseSubtitleFile(file.readText(Charsets.UTF_8)).takeIf { it.isNotEmpty() }
    }

    fun onYouTubePageChanged(url: String) {
        if (classifyMainFrameUrl(url) != EmbeddedNavigationDecision.YOUTUBE_WEB) return
        if (shokalonFocusMode) {
            if (focusVerdict(url, ::isLessonVideo) == FocusVerdict.BLOCK) {
                leaveDistraction()
                return
            }
            if (YouTubeUrlParser.extractVideoId(url) != null) lastLessonUrl = url
        }
        if (url != _state.value.browserUrl) {
            preferences.edit().putString("last_browser_url", url).apply()
            _state.update { it.copy(browserUrl = url) }
        }

        val videoId = YouTubeUrlParser.extractVideoId(url)
        if (videoId == null) {
            clearActiveVideo()
        } else {
            openVideo(videoId)
        }
    }

    internal fun onWebPlaybackSecond(
        videoId: String,
        second: Float,
        liveCaption: LiveCaptionSample? = null,
        sessionId: String = "",
    ) {
        val current = _state.value
        if (current.activeVideoId != videoId || !second.isFinite()) return
        val timeMs = (second.coerceAtLeast(0f) * 1_000).toLong()
        val normalizedSessionId = sessionId.takeIf(String::isNotBlank)
        val sessionChanged =
            normalizedSessionId != null &&
                playbackSessionId?.let { it != normalizedSessionId } == true
        if (normalizedSessionId != null) playbackSessionId = normalizedSessionId
        val seek =
            sessionChanged ||
                timeMs + LIVE_CAPTION_BACKWARD_SEEK_RESET_MS < latestPlaybackSecondMs ||
                timeMs > latestPlaybackSecondMs + 2_000L
        if (seek) {
            liveCaptionTracker.reset()
            captionHighlightResolver.reset()
            liveCaptionProgress = null
        }
        trackImmersion(current, videoId, timeMs, seek)
        latestPlaybackSecondMs = timeMs
        playbackKnown = true
        updatePlaybackRequest(seek)
        dubbing.onPlayback(videoId, timeMs, current.playbackPaused, seek)
        if (current.liveFallback) {
            updateLiveFallbackPlayback(videoId, liveCaption, seek)
        } else {
            updateTranscriptPlayback(current, liveCaption, timeMs)
        }
    }

    private fun updateLiveFallbackPlayback(
        videoId: String,
        liveCaption: LiveCaptionSample?,
        seek: Boolean,
    ) {
        val current = _state.value
        captionRecorder.onSample(videoId, liveCaption, seek)
        updateLiveSubtitle(videoId, liveCaption, seek)
        val accepted =
            liveCaption?.takeIf {
                liveTranslationGate.key != null &&
                    liveTranslationKey(it, videoId, current.targetLanguage) == liveTranslationGate.key &&
                    it.revision != rejectedLiveRevision
            }
        liveCaptionProgress = accepted?.let { reconcileLiveCaptionProgress(liveCaptionProgress, it) }
        _state.update {
            it.copy(activeWordIndex = if (it.wordHighlightEnabled) liveCaptionProgress?.activeWordIndex ?: -1 else -1)
        }
    }

    private fun updateTranscriptPlayback(
        current: DualSubUiState,
        liveCaption: LiveCaptionSample?,
        timeMs: Long,
    ) {
        val timedIndex = activeSubtitleIndex(current.segments, timeMs)
        val referenceIndex = if (timedIndex >= 0) timedIndex else nearestSegmentIndex(current.segments, timeMs)
        val livePosition =
            if (shouldCaptureLiveCaptions(current.generatedCaptions, current.wordHighlightEnabled)) {
                val sample =
                    liveCaption?.takeIf {
                        (it.videoId == null || it.videoId == current.activeVideoId) &&
                            (
                                it.languageCode == null ||
                                    it.languageCode.substringBefore('-') == current.resolvedSourceLanguage?.substringBefore('-')
                            )
                    }
                liveCaptionTracker.resolve(sample, current.segments, referenceIndex, timeMs)
            } else {
                null
            }
        val timedWordIndex = activeWordIndex(current.segments, timedIndex, timeMs)
        val position =
            captionHighlightResolver.resolve(
                generatedCaptions = current.generatedCaptions,
                wordHighlightEnabled = current.wordHighlightEnabled,
                timedSegmentIndex = timedIndex,
                timedWordIndex = timedWordIndex,
                livePosition = livePosition,
                playbackTimeMs = timeMs,
            )
        val index = position?.segmentIndex ?: -1
        val wordIndex = position?.wordIndex ?: -1
        if (index != current.currentIndex || wordIndex != current.activeWordIndex) {
            _state.update { it.copy(currentIndex = index, activeWordIndex = wordIndex) }
        }
    }

    private fun updateLiveSubtitle(
        videoId: String,
        sample: LiveCaptionSample?,
        seek: Boolean,
    ) {
        if (seek) rejectedLiveRevision = sample?.revision
        val key = liveTranslationKey(sample?.takeUnless { it.revision == rejectedLiveRevision }, videoId, _state.value.targetLanguage)
        if (!liveTranslationGate.update(key, seek)) return
        liveTranslationJob?.cancel()
        val ticket = liveTranslationGate.generation
        _state.update {
            it.copy(
                liveOriginal = key?.text,
                liveTranslated = null,
                resolvedSourceLanguage = key?.language,
                statusMessage =
                    if (key ==
                        null
                    ) {
                        "Waiting for YouTube captions and their language. Play the video with captions enabled."
                    } else {
                        "Translating live captions…"
                    },
            )
        }
        if (key == null) return
        if (!TranslationLanguages.isSupported(key.language)) {
            _state.update {
                it.copy(
                    statusMessage = "Live translation is not supported for ${TranslationLanguages.displayName(key.language)}.",
                )
            }
            return
        }
        liveTranslationJob =
            viewModelScope.launch {
                try {
                    val translated =
                        debouncedLiveTranslation(
                            key,
                            isCurrent = {
                                liveTranslationGate.accepts(ticket, key) && _state.value.liveFallback &&
                                    _state.value.activeVideoId == videoId
                            },
                            translate = translator::translateSingle,
                        ) ?: return@launch
                    if (liveTranslationGate.accepts(ticket, key) && _state.value.liveFallback && _state.value.activeVideoId == videoId) {
                        _state.update {
                            it.copy(
                                liveTranslated = translated,
                                statusMessage = "Current captions only; paragraph replay is unavailable.",
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (liveTranslationGate.accepts(ticket, key)) {
                        _state.update {
                            it.copy(
                                statusMessage = "Live translation unavailable. Check the connection for the language model download.",
                            )
                        }
                    }
                }
            }
    }

    fun setSourcePreference(language: String) {
        val normalized = language.takeIf { it == "auto" } ?: TranslationLanguages.normalize(language)
        val current = _state.value
        if (!shouldAcceptSourcePreference(normalized, current.availableSourceLanguages)) return
        if (current.sourcePreference == normalized) return
        preferences.edit().putString("preferred_caption_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(sourcePreference = normalized) }
        val videoId = _state.value.activeVideoId ?: return
        loadVideo(videoId, showPanel = true)
    }

    fun setTargetLanguage(language: String) {
        val normalized = TranslationLanguages.normalize(language)
        if (!TranslationLanguages.isSupported(normalized) || _state.value.targetLanguage == normalized) return
        preferences.edit().putString("target_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(targetLanguage = normalized, liveTranslated = null) }
    }

    fun completeOnboarding(
        nativeLanguage: String,
        learningLanguage: String,
    ) {
        val languages = normalizedOnboardingLanguages(nativeLanguage, learningLanguage) ?: return
        val (native, learning) = languages
        preferences.edit().putString("preferred_caption_language", learning).apply()
        preferences.edit().putString("target_language", native).apply()
        _state.update { it.copy(sourcePreference = learning, targetLanguage = native) }
        warmTranslationModel(sourceLanguage = learning, targetLanguage = native)
        finishOnboarding()
    }

    fun skipOnboarding() = finishOnboarding()

    private fun finishOnboarding() {
        preferences.edit().putBoolean("onboarding_completed", true).apply()
        _state.update { it.copy(onboardingCompleted = true) }
    }

    private fun warmTranslationModel(
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        translationWarmupJob?.cancel()
        translationWarmupJob =
            viewModelScope.launch {
                try {
                    translator.prepare(sourceLanguage, targetLanguage)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Warm-up is opportunistic. The normal translation path retries
                    // model preparation and surfaces any real failure to the user.
                }
            }
    }

    fun completeGuide() {
        val editor = preferences.edit().putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
        // The goal step follows the guide, even if the app closes before it is answered.
        if (!preferences.contains(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE)) {
            editor.putBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, false)
        }
        editor.apply()
        _state.update { it.copy(guideCompleted = true) }
    }

    fun setDailyGoal(minutes: Int) {
        val goal = storedDailyGoalMinutes(minutes)
        preferences.edit().putInt(DAILY_GOAL_MINUTES_PREFERENCE, goal).apply()
        _state.update { it.copy(dailyGoalMinutes = goal) }
    }

    /** Finishes the first-launch goal step; a null goal means the user skipped it. */
    fun completeDailyGoalPrompt(minutes: Int?) {
        if (minutes != null) setDailyGoal(minutes)
        preferences.edit().putBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, true).apply()
        _state.update { it.copy(dailyGoalPromptCompleted = true) }
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
    }

    fun setPortraitPanelOffsetFraction(offsetFraction: Float) {
        val normalized = normalizePortraitPanelOffsetFraction(offsetFraction)
        preferences.edit().putFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, normalized).apply()
        _state.update { it.copy(portraitPanelOffsetFraction = normalized) }
    }

    fun resetPortraitPanelPosition() {
        preferences.edit().remove(PORTRAIT_PANEL_OFFSET_PREFERENCE).apply()
        _state.update {
            it.copy(portraitPanelOffsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION)
        }
    }

    fun setLandscapeSplitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("landscape_split_enabled", enabled).apply()
        _state.update { it.copy(landscapeSplitEnabled = enabled) }
    }

    fun setOriginalSubtitleColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_ORIGINAL_COLOR_KEY,
        )

    fun setTranslatedSubtitleColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_TRANSLATED_COLOR_KEY,
        )

    fun setHighlightColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_HIGHLIGHT_COLOR_KEY,
        )

    fun setWordHighlightEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, enabled).apply()
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        _state.update { it.copy(wordHighlightEnabled = enabled, activeWordIndex = -1) }
    }

    fun setCustomColorsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(customColorsEnabled = enabled) }
    }

    fun setCaptionFormat(format: CaptionFormat) {
        if (_state.value.captionFormat == format) return
        preferences.edit().putString(CAPTION_FORMAT_PREFERENCE, format.storageValue).apply()
        _state.update { it.copy(captionFormat = format) }
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
    }

    fun setLockOverlayToVideo(locked: Boolean) {
        preferences.edit().putBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, locked).apply()
        _state.update { it.copy(lockOverlayToVideo = locked) }
    }

    fun setPreloadModelsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(preloadModelsEnabled = enabled) }
        if (enabled) {
            val source = _state.value.sourcePreference.takeUnless { it == "auto" } ?: "en"
            warmTranslationModel(sourceLanguage = source, targetLanguage = _state.value.targetLanguage)
        }
    }

    fun setNaturalSubtitlesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(NATURAL_SUBTITLES_PREFERENCE, enabled).apply()
        _state.update { it.copy(naturalSubtitlesEnabled = enabled) }
        val videoId = _state.value.activeVideoId
        if (videoId != null) {
            loadVideo(videoId = videoId, showPanel = _state.value.subtitlePanelVisible)
        }
    }

    fun setWordLearningEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_LEARNING_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(wordLearningEnabled = enabled) }
    }

    fun setWordLearningTarget(target: String) {
        preferences.edit().putString(WORD_LEARNING_TARGET_PREFERENCE, target).apply()
        _state.update { it.copy(wordLearningTarget = target) }
    }

    fun setTapToLearnEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(TAP_TO_LEARN_PREFERENCE, enabled).apply()
        _state.update { it.copy(tapToLearnEnabled = enabled) }
    }

    fun setWordLearningActiveOnly(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, enabled).apply()
        _state.update { it.copy(wordLearningActiveOnly = enabled) }
    }

    fun selectLearningWord(tap: WordTap?) {
        // SHOKALON: a word of the Uzbek translation opens "So\u2018zni almashtirish" instead of word learning.
        if (shokalonFocusMode && tap?.translated == true && tap.segment != null) {
            wordToReplace.value = tap
            return
        }
        val current = _state.value
        val source = com.kienhoang.dualsubreplay.data.learningSourceLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        _state.update {
            it.copy(
                selectedLearningWord =
                    tap?.let { selected ->
                        com.kienhoang.dualsubreplay.data.learningSelection(
                            if (current.liveFallback) selected.copy(segment = null) else selected,
                            source,
                            current.targetLanguage,
                            current.activeVideoId.takeUnless { current.liveFallback },
                        )
                    },
            )
        }
    }

    fun setAutoPronounce(enabled: Boolean) {
        preferences.edit().putBoolean("auto_pronounce", enabled).apply()
        _state.update { it.copy(autoPronounce = enabled) }
    }

    /** Translates [text] from a subtitle line: original lines into the target language, translated lines back. */
    internal suspend fun translateSubtitleText(
        text: String,
        translated: Boolean,
    ): String {
        val current = _state.value
        val source = com.kienhoang.dualsubreplay.data.learningSourceLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        return if (translated) {
            translator.translateSingle(current.targetLanguage, source, text)
        } else {
            translator.translateSingle(source, current.targetLanguage, text)
        }
    }

    /** An approved UZI glossary term wins over the online word translation. */
    internal suspend fun translateSelection(selection: LearningWordSelection): String =
        glossaryEntryFor(selection)?.uzbek
            ?: translator.translateSingle(selection.wordLanguage, selection.meaningLanguage, selection.token.text)

    /** The glossary term covering a tapped English word or phrase ("vein" inside "portal vein"), if any. */
    internal fun glossaryEntryFor(selection: LearningWordSelection): com.kienhoang.dualsubreplay.data.GlossaryEntry? {
        if (selection.translated) return null
        if (!com.kienhoang.dualsubreplay.data.glossaryApplies(selection.wordLanguage, selection.meaningLanguage)) return null
        val glossary = com.kienhoang.dualsubreplay.data.UziGlossaryStore.glossary.value ?: return null
        val text = selection.segment?.originalText
        val parts = selection.parts.ifEmpty { listOf(selection.token) }
        val start = parts.minOf { it.startIndex }
        val end = parts.maxOf { it.endIndex }
        val inCaption = text?.takeIf { start in 0..end && end <= it.length }?.let { glossary.termAt(it, start, end)?.entry }
        return inCaption ?: glossary.lookup(selection.token.text)
    }

    internal suspend fun saveWord(
        selection: LearningWordSelection,
        meaning: String,
        online: Boolean,
    ): SavedWord = vocabulary.save(savedWordFrom(selection, meaning, online))

    suspend fun translateWord(word: String): String {
        val current = _state.value
        val source = current.resolvedSourceLanguage ?: current.sourcePreference.takeUnless { it == "auto" } ?: "en"
        return translator.translateSingle(source, current.targetLanguage, word)
    }

    private fun setSubtitleColorKey(
        preferenceKey: String,
        key: String,
        fallback: String,
    ) {
        val normalized = storedSubtitleColorKey(key, fallback)
        val currentKey =
            when (preferenceKey) {
                SUBTITLE_ORIGINAL_COLOR_PREFERENCE -> _state.value.originalColorKey
                SUBTITLE_TRANSLATED_COLOR_PREFERENCE -> _state.value.translatedColorKey
                else -> _state.value.highlightColorKey
            }
        if (currentKey == normalized) return
        preferences.edit().putString(preferenceKey, normalized).apply()
        _state.update { state ->
            when (preferenceKey) {
                SUBTITLE_ORIGINAL_COLOR_PREFERENCE -> state.copy(originalColorKey = normalized)
                SUBTITLE_TRANSLATED_COLOR_PREFERENCE -> state.copy(translatedColorKey = normalized)
                else -> state.copy(highlightColorKey = normalized)
            }
        }
    }

    /**
     * Restores every user-facing setting to its factory default (issue #22).
     * The browser URL is deliberately kept so the session is not disturbed.
     */
    fun resetAllSettings() {
        val editor = preferences.edit()
        RESETTABLE_SETTING_KEYS.forEach(editor::remove)
        editor.apply()
        _state.update { it.copy(autoPronounce = true) }
        latestPlaybackSecondMs = 0L
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        _state.update { current ->
            current.copy(
                sourcePreference = "auto",
                targetLanguage = "vi",
                fontScale = 1f,
                portraitPanelOffsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                landscapeSplitEnabled = true,
                originalColorKey = DEFAULT_ORIGINAL_COLOR_KEY,
                translatedColorKey = DEFAULT_TRANSLATED_COLOR_KEY,
                highlightColorKey = DEFAULT_HIGHLIGHT_COLOR_KEY,
                wordHighlightEnabled = true,
                customColorsEnabled = true,
                originalVisibility = CaptionVisibility.ALWAYS,
                translatedVisibility = CaptionVisibility.ALWAYS,
                captionFormat = defaultCaptionFormat(BUILD_PREFERS_WHOLE_SENTENCE),
                lockOverlayToVideo = false,
                preloadModelsEnabled = true,
                naturalSubtitlesEnabled = true,
                wordLearningEnabled = true,
                wordLearningTarget = "both",
                wordLearningActiveOnly = true,
                tapToLearnEnabled = true,
                selectedLearningWord = null,
            )
        }
        _state.value.activeVideoId?.let { loadVideo(it, showPanel = _state.value.subtitlePanelVisible) }
    }

    /**
     * Retries what failed. While the original captions are showing and only translation stopped,
     * translation starts again from the stored captions; otherwise the video's captions reload.
     */
    fun retryCaptions() {
        val current = _state.value
        val videoId = current.activeVideoId ?: return
        if (onlyTranslationFailed(current)) {
            _state.update { it.copy(translationError = null, statusMessage = "Preparing nearby translations…") }
            playbackRequests.update { it.copy(translationAttempt = it.translationAttempt + 1) }
            return
        }
        loadVideo(videoId, showPanel = true)
    }

    /** Uses the open video's recorded captions as its subtitles: replay, translation, export and voice then work. */
    fun useRecordedCaptions() = changeRecording(complete = true) { "Yozib olingan subtitr ishlatilmoqda: ${it.cues} qator." }

    /** Goes back to recording, for parts of the video not recorded yet. */
    fun continueRecordingCaptions() =
        changeRecording(complete = false) { "Yozish davom etadi. Videoning yozilmagan qismlarini ko\u2018ring." }

    private fun changeRecording(
        complete: Boolean,
        message: (RecordingSummary) -> String,
    ) {
        val videoId = _state.value.activeVideoId ?: return
        viewModelScope.launch {
            val summary =
                try {
                    captionRecorder.setComplete(videoId, complete)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    toast(error.message ?: "Yozib olingan subtitrni saqlab bo\u2018lmadi.")
                    return@launch
                }
            if (summary == null || summary.cues == 0) {
                toast("Bu video uchun hali subtitr yozilmagan.")
                return@launch
            }
            toast(message(summary))
            if (_state.value.activeVideoId == videoId) loadVideo(videoId, showPanel = true)
        }
    }

    /** Deletes the open video's recorded captions. */
    fun deleteRecordedCaptions() {
        val videoId = _state.value.activeVideoId ?: return
        viewModelScope.launch {
            val wasUsed = _state.value.recordedCaptions?.complete == true
            captionRecorder.delete(videoId)
            toast("Yozib olingan subtitr o\u2018chirildi.")
            if (wasUsed && _state.value.activeVideoId == videoId) loadVideo(videoId, showPanel = true)
        }
    }

    fun showSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = true) }

    fun hideSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = false) }

    private fun openVideo(videoId: String) {
        loadReviewMarks(videoId)
        if (_state.value.activeVideoId == videoId) return
        loadVideo(videoId, showPanel = true)
        // Every other loadVideo call reloads the open video, whose recording is already open.
        captionRecorder.open(videoId)
    }

    private fun clearActiveVideo() {
        if (_state.value.activeVideoId == null) return
        importedTranslation = null
        captionRecorder.close()
        dubbing.reset()
        immersionTracker.reset()
        flushImmersion()
        loadGeneration += 1
        loadingJob?.cancel()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        latestPlaybackSecondMs = 0L
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        playbackSessionId = null
        playbackKnown = false
        playbackRequests.value = CaptionPlaybackRequest()
        _state.update {
            it.copy(
                activeVideoId = null,
                liveFallback = false,
                liveOriginal = null,
                liveTranslated = null,
                retryingTranscript = false,
                subtitlePanelVisible = true,
                availableSourceLanguages = emptyList(),
                resolvedSourceLanguage = null,
                generatedCaptions = false,
                segments = emptyList(),
                importedTranslationCues = 0,
                recordedCaptions = null,
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.IDLE,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    private fun loadVideo(
        videoId: String,
        showPanel: Boolean,
    ) {
        val generation = ++loadGeneration
        loadingJob?.cancel()
        liveCaptionTracker.reset()
        liveCaptionProgress = null
        val preserveLive = _state.value.liveFallback && _state.value.activeVideoId == videoId
        if (!preserveLive) {
            rejectedLiveRevision = null
            liveTranslationGate.reset()
            liveTranslationJob?.cancel()
        }
        // Reloading the same video (language change, retry) keeps tracking the
        // current position so subtitles resume exactly where playback is.
        if (shouldResetPlaybackClock(_state.value.activeVideoId, videoId)) {
            latestPlaybackSecondMs = 0L
            playbackKnown = false
            playbackSessionId = null
            captionHighlightResolver.reset()
        }
        playbackRequests.value = CaptionPlaybackRequest()
        _state.update {
            it.copy(
                liveFallback = preserveLive,
                liveOriginal = if (preserveLive) it.liveOriginal else null,
                liveTranslated = if (preserveLive) it.liveTranslated else null,
                retryingTranscript = preserveLive,
                activeVideoId = videoId,
                playbackPaused = if (playbackKnown) it.playbackPaused else true,
                subtitlePanelVisible = showPanel,
                availableSourceLanguages = emptyList(),
                resolvedSourceLanguage = if (preserveLive) it.resolvedSourceLanguage else null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.LOADING_CAPTIONS,
                statusMessage = if (preserveLive) it.statusMessage else "Finding the best caption track…",
                errorMessage = null,
            )
        }
        updatePlaybackRequest()
        loadingJob =
            viewModelScope.launch {
                var rawStore: SubtitleStore? = null
                try {
                    val preferredLanguages = preferredCaptionLanguages(_state.value.sourcePreference)
                    val natural = _state.value.naturalSubtitlesEnabled
                    val track =
                        withContext(Dispatchers.IO) {
                            persistCaptionTrack(fetchCaptionTrack(videoId, preferredLanguages), natural) { rawStore = it }
                        }
                    if (!isCurrentLoad(_state.value, videoId, generation)) return@launch
                    liveTranslationGate.reset()
                    liveTranslationJob?.cancel()

                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            liveFallback = false,
                            liveOriginal = null,
                            liveTranslated = null,
                            retryingTranscript = false,
                            resolvedSourceLanguage = track.languageCode,
                            sourcePreference =
                                resolvedSourcePreference(
                                    current.sourcePreference,
                                    track.languageCode,
                                ),
                            availableSourceLanguages = track.availableLanguages,
                            generatedCaptions = track.isGenerated,
                            segments = emptyList(),
                            stage = LoadStage.TRANSLATING,
                            statusMessage = translationStartingMessage(current.targetLanguage),
                        )
                    }

                    translationSettings().collectLatest { (format, target) ->
                        runStoredTranslation(checkNotNull(rawStore), videoId, generation, track.languageCode, target, format, natural)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            stage = LoadStage.READY,
                            liveFallback = true,
                            retryingTranscript = false,
                            statusMessage =
                                if (current.liveOriginal !=
                                    null
                                ) {
                                    current.statusMessage
                                } else {
                                    "Waiting for YouTube captions and their language. Play the video with captions enabled."
                                },
                            errorMessage = null,
                        )
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { rawStore?.close() }
                }
            }
    }

    private suspend fun persistCaptionTrack(
        track: CaptionTrackResult,
        natural: Boolean,
        onStored: (SubtitleStore) -> Unit,
    ): CaptionTrackResult {
        currentCoroutineContext().ensureActive()
        val merged = SubtitleMerger.merge(track.cues, enhancedNaturalFlow = natural)
        if (merged.isEmpty()) throw CaptionUnavailableException("This caption track contains no readable text.")
        onStored(SubtitleStore.create(subtitleDirectory, merged))
        // Do not retain every raw cue across the long-lived translation coroutine.
        return track.copy(cues = emptyList())
    }

    /**
     * The caption format and target language to translate with. The UZI glossary switch and the uz
     * build's translator choice change what is sent, so changing either re-translates the open video.
     */
    private fun translationSettings() =
        combine(
            _state.map { it.captionFormat to it.targetLanguage },
            com.kienhoang.dualsubreplay.dubbing.DubbingSettings.glossaryInTranslation,
            com.kienhoang.dualsubreplay.dubbing.DubbingSettings.translationProvider,
        ) { formatAndTarget, glossary, provider -> Triple(formatAndTarget, glossary, provider) }
            .distinctUntilChanged()
            .map { (formatAndTarget, _, _) -> formatAndTarget }

    private suspend fun runStoredTranslation(
        rawStore: SubtitleStore,
        videoId: String,
        generation: Long,
        sourceLanguage: String,
        targetLanguage: String,
        format: CaptionFormat,
        natural: Boolean,
    ) {
        var displayStore: SubtitleStore? = null
        if (!restoreImportedTranslation(videoId, generation)) return
        claude.onLessonLoaded(videoId, targetLanguage, hasStoredTranslation = importedTranslation?.first == videoId)
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        _state.update {
            it.copy(
                segments = emptyList(),
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.LOADING_CAPTIONS,
                errorMessage = null,
                translationError = null,
                isDownloadingTranslationModel = false,
                statusMessage = "Preparing subtitles near playback…",
            )
        }
        var following = false

        suspend fun follow(translate: suspend (String) -> String) {
            following = true
            translatePlaybackWindow(
                checkNotNull(displayStore),
                playbackRequests,
                translate,
                onTranslationFailure = { error -> showTranslationUnavailable(videoId, generation, error) },
            ) { rows, preparing ->
                publishSubtitleWindow(videoId, generation, rows, preparing)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                // A stored translation is punctuated, unlike YouTube's automatic English: its sentences set the rows.
                val stored = importedTranslation?.takeIf { it.first == videoId }?.second
                displayStore =
                    if (stored != null) {
                        prepareImportedSentenceStore(rawStore, stored, subtitleDirectory)
                    } else {
                        prepareCaptionDisplayStore(
                            source = rawStore,
                            directory = subtitleDirectory,
                            format = format,
                            natural = natural,
                        )
                    }
                // The uz build's Gemini translator sends the coming sentences together, so it needs them.
                if (BUILD_PREFERS_WHOLE_SENTENCE) {
                    com.kienhoang.dualsubreplay.translation.TranslationLookahead.sentences =
                        com.kienhoang.dualsubreplay.translation.sentenceTexts(checkNotNull(displayStore))
                }
            }
            if (importedTranslation?.first == videoId) {
                // The user's own translation replaces machine translation; rows are filled in publishSubtitleWindow.
                follow { "" }
            }
            try {
                translator.withSession(sourceLanguage, targetLanguage, onDownloadingChange = { downloading ->
                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) {
                            current
                        } else {
                            current.copy(
                                isDownloadingTranslationModel = downloading,
                                statusMessage = if (downloading) "Downloading translation model…" else "Preparing nearby translations…",
                            )
                        }
                    }
                }) { translate -> follow(translate) }
            } catch (error: Exception) {
                if (error is CancellationException || following) throw error
                // The pair cannot be translated at all (an unsupported language): still show the captions.
                follow { throw error }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { current ->
                if (!isCurrentLoad(current, videoId, generation)) {
                    current
                } else {
                    current.copy(
                        stage = LoadStage.ERROR,
                        isDownloadingTranslationModel = false,
                        statusMessage = null,
                        errorMessage = error.message ?: "The subtitles could not be loaded. Retry to continue.",
                    )
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { displayStore?.close() }
        }
    }

    private fun showTranslationUnavailable(
        videoId: String,
        generation: Long,
        error: Exception,
    ) {
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            current.copy(
                isDownloadingTranslationModel = false,
                translationError = translationFailureMessage(error),
                statusMessage = ORIGINAL_CAPTIONS_ONLY_STATUS,
            )
        }
    }

    private fun publishSubtitleWindow(
        videoId: String,
        generation: Long,
        windowRows: List<SubtitleSegment>,
        preparing: Boolean,
    ) {
        val imported = importedTranslation?.takeIf { it.first == videoId }?.second
        val rows = if (imported == null) windowRows else applyImportedTranslation(windowRows, imported)
        dubbing.onRows(videoId, rows, _state.value.targetLanguage)
        // The window slides forward every few rows during playback. Keep the highlight on the same
        // row instead of recomputing it from timestamps, which can point at the previous sentence.
        val shift = windowShift(_state.value.segments, rows)
        if (shift == null) {
            liveCaptionTracker.reset()
            captionHighlightResolver.reset()
        } else if (shift > 0) {
            liveCaptionTracker.shift(shift)
            captionHighlightResolver.shift(shift)
        }
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            val index = activeSubtitleIndex(rows, latestPlaybackSecondMs)
            val keptIndex =
                when {
                    shift == null -> null
                    current.currentIndex < 0 -> -1
                    else -> (current.currentIndex - shift).takeIf { it in rows.indices }
                }
            current.copy(
                segments = rows,
                currentIndex = keptIndex ?: index,
                activeWordIndex =
                    when {
                        keptIndex != null -> current.activeWordIndex
                        current.wordHighlightEnabled -> activeWordIndex(rows, index, latestPlaybackSecondMs)
                        else -> -1
                    },
                stage = if (preparing) LoadStage.TRANSLATING else LoadStage.READY,
                statusMessage =
                    when {
                        current.translationError != null -> ORIGINAL_CAPTIONS_ONLY_STATUS
                        current.playbackPaused -> "Paused · translation resumes on play"
                        preparing -> "Preparing nearby translations…"
                        else -> "Subtitles ready near playback"
                    },
            )
        }
    }

    private fun translationStartingMessage(targetLanguage: String): String =
        "Preparing ${TranslationLanguages.displayName(targetLanguage)} translation…"

    private fun mobileWatchUrl(videoId: String): String = "https://m.youtube.com/watch?v=$videoId"

    private fun isCurrentLoad(
        state: DualSubUiState,
        videoId: String,
        generation: Long,
    ): Boolean = generation == loadGeneration && state.activeVideoId == videoId
}
