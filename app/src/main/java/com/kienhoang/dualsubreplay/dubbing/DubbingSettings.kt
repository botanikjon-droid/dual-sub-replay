package com.kienhoang.dualsubreplay.dubbing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A dubbing voice the user can pick; [id] is the service's voice name. */
internal data class DubbingVoiceOption(
    val id: String,
    val label: String,
)

/**
 * The dub's user settings, saved in their own preferences file. Only a build with a voice
 * (the "uz" build) calls [load]; until then [available] is false and the settings card hides.
 */
internal object DubbingSettings {
    private const val FILE = "dubbing"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOICE = "voice"
    private const val KEY_VIDEO_VOLUME = "video_volume_percent"
    private const val KEY_VIDEO_QUALITY = "video_quality"
    private const val KEY_HOLD_VIDEO = "hold_video"
    private const val KEY_MAX_TEMPO = "max_tempo_percent"
    private const val KEY_SPEECH_UNITS = "speech_units"
    private const val KEY_SPEECH_ACRONYMS = "speech_acronyms"
    private const val KEY_GLOSSARY_IN_TRANSLATION = "glossary_in_translation"
    private const val DEFAULT_VIDEO_VOLUME = 20
    private const val DEFAULT_MAX_TEMPO_PERCENT = 150

    private val _available = MutableStateFlow(false)
    private val _enabled = MutableStateFlow(true)
    private val _voiceId = MutableStateFlow("")
    private val _videoVolumePercent = MutableStateFlow(DEFAULT_VIDEO_VOLUME)
    private val _videoQuality = MutableStateFlow(VIDEO_QUALITY_AUTO)
    private val _holdVideo = MutableStateFlow(true)
    private val _maxTempoPercent = MutableStateFlow(DEFAULT_MAX_TEMPO_PERCENT)
    private val _stats = MutableStateFlow(DubbingStats())
    private val _speechOptions = MutableStateFlow(SpeechOptions())
    private val _glossaryInTranslation = MutableStateFlow(true)
    private var preferences: SharedPreferences? = null

    val available: StateFlow<Boolean> = _available
    val enabled: StateFlow<Boolean> = _enabled
    val voiceId: StateFlow<String> = _voiceId

    /** How loud the video stays while the dub speaks, 0-100 % of its normal volume. */
    val videoVolumePercent: StateFlow<Int> = _videoVolumePercent

    /** The highest YouTube quality to load, as a player level such as "medium" (360p). */
    val videoQuality: StateFlow<String> = _videoQuality

    /** Pause the video until a sentence's speech is ready or finished, so none is cut or lost. */
    val holdVideo: StateFlow<Boolean> = _holdVideo

    /** The fastest the dub may speak to fit its sentence, in percent (150 = 1.5x). */
    val maxTempoPercent: StateFlow<Int> = _maxTempoPercent

    /** Experimental pronunciation rules (units, abbreviations); both off until a doctor confirms them by ear. */
    val speechOptions: StateFlow<SpeechOptions> = _speechOptions

    /** Send approved UZI glossary terms to the translator in place of the English terms (on by default). */
    val glossaryInTranslation: StateFlow<Boolean> = _glossaryInTranslation

    /** What the dub did in the current video, for the settings card. */
    val stats: StateFlow<DubbingStats> = _stats
    var voices: List<DubbingVoiceOption> = emptyList()
        private set

    fun load(
        context: Context,
        voices: List<DubbingVoiceOption>,
    ) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        preferences = saved
        this.voices = voices
        _enabled.value = saved.getBoolean(KEY_ENABLED, true)
        _voiceId.value = saved.getString(KEY_VOICE, null)?.takeIf { id -> voices.any { it.id == id } } ?: voices.first().id
        _videoVolumePercent.value = saved.getInt(KEY_VIDEO_VOLUME, DEFAULT_VIDEO_VOLUME).coerceIn(0, 100)
        _videoQuality.value =
            saved.getString(KEY_VIDEO_QUALITY, null)?.takeIf { id -> VIDEO_QUALITIES.any { it.id == id } }
                ?: DEFAULT_VIDEO_QUALITY
        _holdVideo.value = saved.getBoolean(KEY_HOLD_VIDEO, true)
        _maxTempoPercent.value =
            saved.getInt(KEY_MAX_TEMPO, DEFAULT_MAX_TEMPO_PERCENT).takeIf { it in TEMPO_PERCENTS } ?: DEFAULT_MAX_TEMPO_PERCENT
        _speechOptions.value =
            SpeechOptions(
                readUnits = saved.getBoolean(KEY_SPEECH_UNITS, false),
                expandAcronyms = saved.getBoolean(KEY_SPEECH_ACRONYMS, false),
            )
        _glossaryInTranslation.value = saved.getBoolean(KEY_GLOSSARY_IN_TRANSLATION, true)
        _available.value = true
    }

    fun setGlossaryInTranslation(value: Boolean) {
        _glossaryInTranslation.value = value
        preferences?.edit()?.putBoolean(KEY_GLOSSARY_IN_TRANSLATION, value)?.apply()
    }

    fun setSpeechOptions(options: SpeechOptions) {
        _speechOptions.value = options
        preferences
            ?.edit()
            ?.putBoolean(KEY_SPEECH_UNITS, options.readUnits)
            ?.putBoolean(KEY_SPEECH_ACRONYMS, options.expandAcronyms)
            ?.apply()
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        preferences?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    fun setVoice(id: String) {
        _voiceId.value = id
        preferences?.edit()?.putString(KEY_VOICE, id)?.apply()
    }

    fun setVideoVolumePercent(value: Int) {
        _videoVolumePercent.value = value.coerceIn(0, 100)
        preferences?.edit()?.putInt(KEY_VIDEO_VOLUME, _videoVolumePercent.value)?.apply()
    }

    fun setHoldVideo(value: Boolean) {
        _holdVideo.value = value
        preferences?.edit()?.putBoolean(KEY_HOLD_VIDEO, value)?.apply()
    }

    fun setMaxTempoPercent(percent: Int) {
        if (percent !in TEMPO_PERCENTS) return
        _maxTempoPercent.value = percent
        preferences?.edit()?.putInt(KEY_MAX_TEMPO, percent)?.apply()
    }

    fun publishStats(stats: DubbingStats) {
        _stats.value = stats
    }

    fun setVideoQuality(id: String) {
        if (VIDEO_QUALITIES.none { it.id == id }) return
        _videoQuality.value = id
        preferences?.edit()?.putString(KEY_VIDEO_QUALITY, id)?.apply()
    }
}

/** Allowed fastest-speech settings, in percent of normal speed. */
internal val TEMPO_PERCENTS = listOf(130, 150, 180)

/** What the dub did in the current video. */
internal data class DubbingStats(
    val spoken: Int = 0,
    val skipped: Int = 0,
    val held: Int = 0,
    val heldMs: Long = 0,
)

internal const val VIDEO_QUALITY_AUTO = "auto"

/** 360p keeps lectures readable while using about a third of the data of 720p. */
private const val DEFAULT_VIDEO_QUALITY = "medium"

/** How often the page is checked, since YouTube picks its own quality for every new video. */
internal const val VIDEO_QUALITY_CHECK_MS = 3_000L

/** YouTube player quality levels, lowest first, with the labels the user sees. */
internal val VIDEO_QUALITIES =
    listOf(
        DubbingVoiceOption("tiny", "144p"),
        DubbingVoiceOption("small", "240p"),
        DubbingVoiceOption("medium", "360p"),
        DubbingVoiceOption("large", "480p"),
        DubbingVoiceOption(VIDEO_QUALITY_AUTO, "Avto"),
    )

/**
 * A page script that caps the YouTube player at [quality]: it picks the best available level not
 * above it (or the lowest one) and sets it only when the player is not already there. Null when
 * there is nothing to do: no build with these settings, or "Auto" chosen.
 */
internal fun videoQualityScript(
    quality: String = DubbingSettings.videoQuality.value,
    available: Boolean = DubbingSettings.available.value,
): String? {
    if (!available || quality == VIDEO_QUALITY_AUTO || VIDEO_QUALITIES.none { it.id == quality }) return null
    return """
        (function(want){
          var p=document.getElementById('movie_player');
          if(!p||typeof p.getAvailableQualityLevels!=='function'||typeof p.setPlaybackQualityRange!=='function')return 'none';
          var order=['tiny','small','medium','large','hd720','hd1080','hd1440','hd2160','highres'];
          var levels=(p.getAvailableQualityLevels()||[]).filter(function(l){return order.indexOf(l)>=0;});
          if(!levels.length)return 'wait';
          var limit=order.indexOf(want),pick=null;
          levels.forEach(function(l){var i=order.indexOf(l);if(i<=limit&&(pick===null||i>order.indexOf(pick)))pick=l;});
          if(pick===null)levels.forEach(function(l){if(pick===null||order.indexOf(l)<order.indexOf(pick))pick=l;});
          if(typeof p.getPlaybackQuality==='function'&&p.getPlaybackQuality()===pick)return 'ok:'+pick;
          p.setPlaybackQualityRange(pick,pick);
          if(typeof p.setPlaybackQuality==='function')p.setPlaybackQuality(pick);
          return 'set:'+pick;
        })('$quality')
        """.trimIndent()
}
