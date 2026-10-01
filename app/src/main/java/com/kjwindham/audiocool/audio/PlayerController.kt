package com.kjwindham.audiocool.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import com.kjwindham.audiocool.data.NoteFocus
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays one recording at a time. */
object PlayerController {
    data class State(
        val sessionId: String? = null,
        val recId: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        /** True once the user has played or seeked, so new notes can link to the playback position. */
        val engaged: Boolean = false,
        /** The note last tapped to play, if any. */
        val focus: NoteFocus? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    // Until a seek completes, getCurrentPosition() can still report the old position.
    private var seekTarget: Long? = null
    private var seekStartedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    fun isLoaded(sessionId: String, recId: String): Boolean =
        player != null && _state.value.sessionId == sessionId && _state.value.recId == recId

    /** Loads [rec] unless it's already loaded. Returns false if it can't be played. */
    fun load(sessionId: String, rec: Recording): Boolean {
        if (isLoaded(sessionId, rec.id)) return true
        release()
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Look the recording up again: its file may have just been converted to .m4a.
            val latest = SessionRepository.get(sessionId)?.recording(rec.id) ?: rec
            p.setDataSource(SessionRepository.audioFile(sessionId, latest).absolutePath)
            p.prepare()
            p.setOnCompletionListener { onComplete() }
            p.setOnSeekCompleteListener { seekTarget = null }
            player = p
            _state.update { State(sessionId = sessionId, recId = rec.id, durationMs = p.duration.toLong(), speed = it.speed) }
            true
        } catch (e: Exception) {
            p.release()
            _state.update { State(speed = it.speed, error = "Can't play this recording") }
            false
        }
    }

    fun playFrom(sessionId: String, rec: Recording, positionMs: Long, focus: NoteFocus? = null) {
        if (!load(sessionId, rec)) return
        seekTo(positionMs)
        _state.update { it.copy(focus = focus) }
        play()
    }

    fun play() {
        val p = player ?: return
        val st = _state.value
        if (st.durationMs > 0 && st.positionMs >= st.durationMs - 250) seekTo(0)
        p.start()
        applySpeed(p)
        _state.update { it.copy(isPlaying = true, engaged = true) }
        startTicker()
    }

    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false, positionMs = positionMs()) }
    }

    fun toggle() = if (_state.value.isPlaying) pause() else play()

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        val target = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        seekTarget = target
        seekStartedAt = SystemClock.elapsedRealtime()
        p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _state.update { it.copy(positionMs = target, engaged = true) }
    }

    fun skipBy(deltaMs: Long) = seekTo(positionMs() + deltaMs)

    /** The playback position; while a seek is in flight, its target (for at most 2 s, in case the callback never comes). */
    fun positionMs(): Long {
        val p = player ?: return 0L
        val target = seekTarget
        if (target != null) {
            if (SystemClock.elapsedRealtime() - seekStartedAt < 2_000) return target
            seekTarget = null
        }
        return p.currentPosition.toLong()
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        player?.let { if (it.isPlaying) applySpeed(it) }
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        seekTarget = null
        _state.update { State(speed = it.speed) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // Setting PlaybackParams on a paused MediaPlayer starts playback, so only call this while playing.
    private fun applySpeed(p: MediaPlayer) {
        runCatching { p.playbackParams = p.playbackParams.setSpeed(_state.value.speed) }
    }

    private fun onComplete() {
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                if (player != null) _state.update { it.copy(positionMs = positionMs()) }
                delay(100)
            }
        }
    }
}
