package com.kienhoang.dualsubreplay.dubbing

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Synthesized speech for one line, cached on disk. */
internal data class DubClip(
    val file: File,
    val durationMs: Long,
)

/** Turns translated text into speech. Only the "uz" build has one (see createDubbingVoice). */
internal interface DubbingVoice {
    /** The language this voice speaks; the dub stays silent unless the target language matches. */
    val language: String

    suspend fun synthesize(text: String): DubClip
}

/** Plays one clip at a time. Kept behind an interface so the controller is testable. */
internal interface ClipPlayer {
    val playing: Boolean

    fun play(
        clip: DubClip,
        speed: Float,
        onEnd: () -> Unit,
    )

    fun pause()

    fun resume()

    fun stop()
}

/**
 * True while a dub clip plays. The YouTube page lowers the video's own volume meanwhile; this is a
 * top-level flow, like the page-state flows in YouTubeBrowserScreen, so no UI wiring changes.
 */
internal val dubbingDucksVideo = MutableStateFlow(false)

/** What the dub wants done with the page video while it waits for speech. */
internal enum class VideoHold {
    /** Keep the video paused until the speech is ready or finished. */
    HELD,

    /** The wait is over: play the video again, if the dub was the one that paused it. */
    RESUME,

    /** The wait is over because the app left the video: forget the hold without playing anything. */
    DROP,
}

/**
 * The dub's hold on the video. Like the ducking flow it is top-level, and YouTubeBrowserScreen
 * turns it into a pause or play of the page.
 */
internal val dubbingHold = MutableStateFlow(VideoHold.RESUME)

/**
 * Pauses the page video for a hold and resumes it after. Only a video this script paused is
 * resumed, so a video the viewer paused or resumed themselves is left as they set it.
 */
internal fun dubbingHoldScript(command: VideoHold): String {
    val body =
        when (command) {
            VideoHold.HELD -> "if(!v.paused){v.__dualsubHeld=true;v.pause();}"
            VideoHold.RESUME ->
                "if(v.__dualsubHeld){v.__dualsubHeld=false;" +
                    "if(v.paused){var r=v.play();if(r&&r.catch)r.catch(function(){});}}"
            VideoHold.DROP -> "v.__dualsubHeld=false;"
        }
    return "(function(){var v=document.querySelector('video');if(!v)return;$body})()"
}

/** Lowers the page video to the chosen share of its volume while a clip plays, then restores it. */
internal fun dubbingVolumeScript(
    duck: Boolean,
    videoVolumePercent: Int = DubbingSettings.videoVolumePercent.value,
): String {
    val body =
        if (duck) {
            val share = videoVolumePercent.coerceIn(0, 100) / 100.0
            "if(v.__dualsubVolume==null)v.__dualsubVolume=v.volume;v.volume=v.__dualsubVolume*$share;"
        } else {
            "if(v.__dualsubVolume!=null){v.volume=v.__dualsubVolume;v.__dualsubVolume=null;}"
        }
    return "(function(){var v=document.querySelector('video');if(!v)return;$body})()"
}

/**
 * Speaks each translated caption sentence when the video reaches it. Fed by AppViewModel with
 * caption windows and the playback clock; with no [voice] every call is a no-op.
 *
 * Speech is usually longer than the sentence it translates, and a slow network can make it late.
 * Instead of cutting or dropping sentences, the controller holds the video paused until a
 * sentence's speech is ready or finished (see [DubScheduler.decide]). It stops holding when the
 * wait gets long, when the viewer takes over, or when the setting is off, and then falls back to
 * the old rule: cut a clip that runs late and skip a line that is too late.
 */
internal class DubbingController(
    private val scope: CoroutineScope,
    private val voice: DubbingVoice?,
    private val player: ClipPlayer = MediaClipPlayer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scheduler = DubScheduler()
    private val clips = HashMap<String, DubClip>()
    private val preparing = HashSet<String>()
    private val failures = HashMap<String, PrepareFailure>()
    private val prepareJobs = mutableListOf<Job>()
    private var holdJob: Job? = null
    private var videoId: String? = null
    private var languageOk = false
    private var switchedOff = false
    private var timeMs = 0L
    private var paused = true
    private var clipsVoiceId = DubbingSettings.voiceId.value
    private var playEndsAtMs = 0L
    private var pausedAtMs = 0L

    private var holding = false
    private var holdStartedAtMs = 0L
    private var holdGraceUntilMs = 0L
    private var holdsSuspendedUntilMs = 0L
    private var waitSinceMs: Long? = null
    private var giveUpsInARow = 0
    private var stats = DubbingStats()

    fun onRows(
        videoId: String,
        rows: List<SubtitleSegment>,
        targetLanguage: String,
    ) {
        if (voice == null) return
        if (videoId != this.videoId) reset(videoId)
        val matches = targetLanguage == voice.language
        if (matches != languageOk) {
            languageOk = matches
            if (!matches) quiet()
        }
        if (!matches) return
        scheduler.update(dubLines(rows))
        scheduler.updatePending(dubPending(rows))
        if (!DubbingSettings.enabled.value) return
        prepareAhead()
        if (holding) evaluate()
    }

    fun onPlayback(
        videoId: String,
        timeMs: Long,
        paused: Boolean,
        seek: Boolean,
    ) {
        if (voice == null || videoId != this.videoId || !languageOk) return
        this.timeMs = timeMs
        if (!followSettings()) return
        if (seek) {
            setHold(false)
            stopClip()
            scheduler.seek(timeMs)
            waitSinceMs = null
        }
        prepareAhead()
        val viewerPaused = viewerPaused(paused)
        if (viewerPaused != this.paused) {
            val now = clock()
            if (viewerPaused) {
                pausedAtMs = now
                player.pause()
            } else {
                // The paused time must not count as speaking time.
                if (player.playing) playEndsAtMs += now - pausedAtMs
                player.resume()
            }
            this.paused = viewerPaused
        }
        evaluate()
    }

    /** Forgets the current video; the next [onRows] starts afresh. */
    fun reset(videoId: String? = null) {
        quiet(drop = true)
        cancelPreparing()
        scheduler.reset()
        clips.clear()
        failures.clear()
        this.videoId = videoId
        languageOk = false
        paused = true
        giveUpsInARow = 0
        holdsSuspendedUntilMs = 0L
        stats = DubbingStats()
        DubbingSettings.publishStats(stats)
    }

    /** Applies the user's settings; false while the dub is switched off. */
    private fun followSettings(): Boolean {
        if (!DubbingSettings.enabled.value) {
            quiet()
            switchedOff = true
            return false
        }
        if (switchedOff) {
            // Switched back on mid-video: what was said meanwhile is not spoken now.
            switchedOff = false
            scheduler.seek(timeMs)
        }
        val voiceId = DubbingSettings.voiceId.value
        if (voiceId != clipsVoiceId) {
            // Another voice: forget clips spoken by the old one and prepare again.
            quiet()
            cancelPreparing()
            clips.clear()
            failures.clear()
            clipsVoiceId = voiceId
        }
        return true
    }

    /**
     * Whether the viewer, not a hold, has the video paused. Right after a hold starts or ends the
     * page still reports the old state for a moment, so that is ignored; a video playing during a
     * hold means the viewer pressed play, which also pauses holding for a while.
     */
    private fun viewerPaused(reportedPaused: Boolean): Boolean {
        val now = clock()
        if (now < holdGraceUntilMs) return false
        if (holding && !reportedPaused) {
            setHold(false)
            holdsSuspendedUntilMs = now + VIEWER_OVERRIDE_PAUSE_MS
            return false
        }
        return reportedPaused && !holding
    }

    private fun holdAllowed(now: Long): Boolean =
        DubbingSettings.holdVideo.value && now >= holdsSuspendedUntilMs && giveUpsInARow < GIVE_UP_LIMIT

    private fun evaluate() {
        if (paused || !DubbingSettings.enabled.value) return
        val now = clock()
        if (player.playing && now > playEndsAtMs + STUCK_CLIP_MS) {
            // The clip should have ended a while ago and said nothing; do not wait for it forever.
            stopClip()
        }
        if (holding && now - holdStartedAtMs > HOLD_HARD_LIMIT_MS) {
            // Something is stuck; never leave the video frozen.
            stopClip()
            giveUpsInARow = GIVE_UP_LIMIT
            setHold(false)
        }
        if (!holdAllowed(now)) {
            setHold(false)
            waitSinceMs = null
            val line = scheduler.next(timeMs, player.playing) { it.key in clips } ?: return
            startLine(line, now)
            return
        }
        var step: DubStep
        var skips = 0
        do {
            val remaining = if (player.playing) (playEndsAtMs - now).coerceAtLeast(0) else null
            val waited = waitSinceMs?.let { now - it } ?: 0L
            step =
                scheduler.decide(
                    timeMs,
                    remaining,
                    waited,
                    MAX_WAIT_MS,
                    ready = { it.key in clips },
                    failed = ::gaveUpOn,
                    holding = holding,
                )
            if (step == DubStep.Skipped) skipped(now)
        } while (step == DubStep.Skipped && ++skips < MAX_SKIPS_PER_TICK)
        when (step) {
            is DubStep.Start -> {
                waitSinceMs = null
                setHold(false)
                giveUpsInARow = 0
                startLine(step.line, now)
            }
            DubStep.HoldForClip -> {
                waitSinceMs = null
                setHold(true)
            }
            DubStep.HoldForAudio -> {
                if (waitSinceMs == null) waitSinceMs = now
                setHold(true)
            }
            DubStep.Idle, DubStep.Skipped -> {
                waitSinceMs = null
                setHold(false)
            }
        }
    }

    private fun gaveUpOn(line: DubLine): Boolean = (failures[line.key]?.count ?: 0) >= MAX_FAILURES

    private fun skipped(now: Long) {
        waitSinceMs = null
        giveUpsInARow++
        stats = stats.copy(skipped = stats.skipped + 1)
        DubbingSettings.publishStats(stats)
        if (giveUpsInARow >= GIVE_UP_LIMIT) holdsSuspendedUntilMs = now
    }

    private fun startLine(
        line: DubLine,
        now: Long,
    ) {
        val clip = clips.getValue(line.key)
        stopClip()
        scheduler.started(line)
        val maxTempo = DubbingSettings.maxTempoPercent.value / PERCENT
        val speed = dubTempo(clip.durationMs, scheduler.slotAfter(line), timeMs - line.startMs, maxTempo)
        dubbingDucksVideo.value = true
        playEndsAtMs = now + (clip.durationMs / speed).toLong()
        player.play(clip, speed) {
            dubbingDucksVideo.value = false
            evaluate()
        }
        stats = stats.copy(spoken = stats.spoken + 1)
        DubbingSettings.publishStats(stats)
        prepareAhead()
    }

    private fun setHold(
        value: Boolean,
        drop: Boolean = false,
    ) {
        if (holding == value) return
        val now = clock()
        holding = value
        holdGraceUntilMs = now + HOLD_GRACE_MS
        if (value) {
            holdStartedAtMs = now
            stats = stats.copy(held = stats.held + 1)
            holdJob?.cancel()
            // Keeps checking while the video is paused, so a hold always ends.
            holdJob =
                scope.launch {
                    while (holding) {
                        delay(HOLD_TICK_MS)
                        evaluate()
                    }
                }
        } else {
            stats = stats.copy(heldMs = stats.heldMs + (now - holdStartedAtMs))
            holdJob?.cancel()
            holdJob = null
        }
        DubbingSettings.publishStats(stats)
        dubbingHold.value =
            when {
                value -> VideoHold.HELD
                drop -> VideoHold.DROP
                else -> VideoHold.RESUME
            }
    }

    /**
     * Stops speaking and holding, e.g. when the dub is switched off. A video the dub paused is
     * played again, unless [drop]: then the viewer left it, so nothing may start playing.
     */
    private fun quiet(drop: Boolean = false) {
        stopClip()
        setHold(false, drop)
        waitSinceMs = null
    }

    private fun stopClip() {
        player.stop()
        dubbingDucksVideo.value = false
    }

    private fun cancelPreparing() {
        prepareJobs.forEach(Job::cancel)
        prepareJobs.clear()
        preparing.clear()
    }

    /** The nearest upcoming line without audio that is not being prepared or waiting to retry. */
    private fun nextToPrepare(): DubLine? =
        scheduler.upcoming(timeMs, PREPARE_AHEAD_MS).firstOrNull { line ->
            val failure = failures[line.key]
            line.key !in clips &&
                line.key !in preparing &&
                (failure == null || (failure.count < MAX_FAILURES && clock() >= failure.retryAtMs))
        }

    /**
     * Keeps up to [WORKERS] syntheses running, nearest line first, so fast speech does not
     * outrun one-at-a-time requests. A failed line is retried after a growing pause, since a
     * busy service usually recovers; only after [MAX_FAILURES] attempts is it given up.
     */
    private fun prepareAhead() {
        val voice = voice ?: return
        prepareJobs.removeAll { !it.isActive }
        while (prepareJobs.size < WORKERS && nextToPrepare() != null) {
            prepareJobs +=
                scope.launch {
                    while (true) {
                        val line = nextToPrepare() ?: break
                        preparing += line.key
                        try {
                            clips[line.key] = voice.synthesize(line.text)
                            failures.remove(line.key)
                            if (holding) evaluate()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            val count = (failures[line.key]?.count ?: 0) + 1
                            val pause = (RETRY_BASE_MS shl (count - 1)).coerceAtMost(RETRY_MAX_MS)
                            failures[line.key] = PrepareFailure(count, clock() + pause)
                        } finally {
                            preparing -= line.key
                        }
                    }
                }
        }
    }

    private data class PrepareFailure(
        val count: Int,
        val retryAtMs: Long,
    )

    private companion object {
        // Short look-ahead: less data is wasted when the viewer skips ahead.
        const val PREPARE_AHEAD_MS = 20_000L
        const val WORKERS = 2
        const val MAX_FAILURES = 4
        const val RETRY_BASE_MS = 2_000L
        const val RETRY_MAX_MS = 15_000L
        const val PERCENT = 100f

        // Holding the video.
        const val MAX_WAIT_MS = 6_000L
        const val HOLD_HARD_LIMIT_MS = 20_000L
        const val STUCK_CLIP_MS = 1_500L
        const val HOLD_TICK_MS = 100L
        const val HOLD_GRACE_MS = 800L
        const val VIEWER_OVERRIDE_PAUSE_MS = 10_000L
        const val GIVE_UP_LIMIT = 2
        const val MAX_SKIPS_PER_TICK = 5
    }
}

/** MediaPlayer without audio focus, so the YouTube video keeps playing underneath. */
internal class MediaClipPlayer : ClipPlayer {
    private var player: MediaPlayer? = null
    override var playing = false
        private set

    override fun play(
        clip: DubClip,
        speed: Float,
        onEnd: () -> Unit,
    ) {
        stop()
        val next = MediaPlayer()
        player = next
        val finish = {
            if (player === next) {
                playing = false
                next.release()
                player = null
                onEnd()
            }
        }
        try {
            next.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            next.setOnCompletionListener { finish() }
            next.setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            next.setDataSource(clip.file.path)
            next.prepare()
            next.playbackParams = PlaybackParams().setSpeed(speed)
            next.start()
            playing = true
        } catch (_: Exception) {
            finish()
        }
    }

    override fun pause() {
        player?.takeIf { playing }?.pause()
    }

    override fun resume() {
        player?.takeIf { playing && !it.isPlaying }?.start()
    }

    override fun stop() {
        val current = player ?: return
        player = null
        playing = false
        try {
            current.stop()
        } catch (_: IllegalStateException) {
            // Not started yet; releasing is enough.
        }
        current.release()
    }
}
