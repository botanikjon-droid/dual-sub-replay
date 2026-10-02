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

/** Lowers the page video to a fifth of its volume while a clip plays, and restores it after. */
internal fun dubbingVolumeScript(duck: Boolean): String {
    val body =
        if (duck) {
            "if(v.__dualsubVolume==null)v.__dualsubVolume=v.volume;v.volume=v.__dualsubVolume*0.2;"
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
) {
    private val scheduler = DubScheduler()
    private val clips = HashMap<String, DubClip>()
    private val failures = HashMap<String, Int>()
    private var videoId: String? = null
    private var timeMs = 0L
    private var paused = true
    private var prepareJob: Job? = null

    fun onRows(
        videoId: String,
        rows: List<SubtitleSegment>,
    ) {
        if (voice == null) return
        if (videoId != this.videoId) reset(videoId)
        scheduler.update(dubLines(rows))
        prepareAhead()
    }

    fun onPlayback(
        videoId: String,
        timeMs: Long,
        paused: Boolean,
        seek: Boolean,
    ) {
        if (voice == null || videoId != this.videoId) return
        this.timeMs = timeMs
        if (seek) {
            stopClip()
            scheduler.seek(timeMs)
            prepareAhead()
        }
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
        prepareJob?.cancel()
        prepareJob = null
        scheduler.reset()
        clips.clear()
        failures.clear()
        this.videoId = videoId
        paused = true
    }

    private fun stopClip() {
        player.stop()
        dubbingDucksVideo.value = false
    }

    /** Synthesizes upcoming lines one after another, nearest first. */
    private fun prepareAhead() {
        val voice = voice ?: return
        if (prepareJob?.isActive == true) return
        prepareJob =
            scope.launch {
                while (true) {
                    val line =
                        scheduler.upcoming(timeMs, PREPARE_AHEAD_MS).firstOrNull {
                            !clips.containsKey(it.key) && (failures[it.key] ?: 0) < MAX_FAILURES
                        } ?: break
                    try {
                        clips[line.key] = voice.synthesize(line.text)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failures[line.key] = (failures[line.key] ?: 0) + 1
                    }
                }
            }
    }

    private companion object {
        const val PREPARE_AHEAD_MS = 45_000L
        const val MAX_FAILURES = 2
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
