package com.kienhoang.dualsubreplay.dubbing

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 */
internal class DubbingController(
    private val scope: CoroutineScope,
    private val voice: DubbingVoice?,
    private val player: ClipPlayer = MediaClipPlayer(),
    private val maxTempo: Float = 1.5f,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scheduler = DubScheduler()
    private val clips = HashMap<String, DubClip>()
    private val preparing = HashSet<String>()
    private val failures = HashMap<String, PrepareFailure>()
    private val prepareJobs = mutableListOf<Job>()
    private var videoId: String? = null
    private var timeMs = 0L
    private var paused = true
    private var clipsVoiceId = DubbingSettings.voiceId.value

    fun onRows(
        videoId: String,
        rows: List<SubtitleSegment>,
    ) {
        if (voice == null) return
        if (videoId != this.videoId) reset(videoId)
        scheduler.update(dubLines(rows))
        if (DubbingSettings.enabled.value) prepareAhead()
    }

    fun onPlayback(
        videoId: String,
        timeMs: Long,
        paused: Boolean,
        seek: Boolean,
    ) {
        if (voice == null || videoId != this.videoId) return
        this.timeMs = timeMs
        if (!followSettings()) return
        if (seek) {
            stopClip()
            scheduler.seek(timeMs)
        }
        prepareAhead()
        if (paused != this.paused) {
            this.paused = paused
            if (paused) player.pause() else player.resume()
        }
        if (paused) return
        val line = scheduler.next(timeMs, player.playing) { clips.containsKey(it.key) } ?: return
        val clip = clips.getValue(line.key)
        stopClip()
        scheduler.started(line)
        val speed = dubTempo(clip.durationMs, scheduler.slotAfter(line), timeMs - line.startMs, maxTempo)
        dubbingDucksVideo.value = true
        player.play(clip, speed) { dubbingDucksVideo.value = false }
        prepareAhead()
    }

    /** Forgets the current video; the next [onRows] starts afresh. */
    fun reset(videoId: String? = null) {
        stopClip()
        cancelPreparing()
        scheduler.reset()
        clips.clear()
        failures.clear()
        this.videoId = videoId
        paused = true
    }

    /** Applies the user's settings; false while the dub is switched off. */
    private fun followSettings(): Boolean {
        if (!DubbingSettings.enabled.value) {
            if (player.playing) stopClip()
            return false
        }
        val voiceId = DubbingSettings.voiceId.value
        if (voiceId != clipsVoiceId) {
            // Another voice: forget clips spoken by the old one and prepare again.
            stopClip()
            cancelPreparing()
            clips.clear()
            failures.clear()
            clipsVoiceId = voiceId
        }
        return true
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
