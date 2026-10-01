package com.kjwindham.audiocool.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.newId
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
import java.io.File
import kotlin.math.log10

/**
 * Owns the MediaRecorder. Notes take their timestamp from [currentOffsetMs], which counts only
 * time spent actually recording, so it lines up with the audio file across pauses.
 */
object RecorderController {
    enum class Status { IDLE, RECORDING, PAUSED }

    data class State(
        val status: Status = Status.IDLE,
        val sessionId: String? = null,
        val recId: String? = null,
        val elapsedMs: Long = 0,
        /** Input level, 0..1, for the meter. */
        val level: Float = 0f,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private var recorder: MediaRecorder? = null
    private var accumulatedMs = 0L
    private var runStartedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun currentOffsetMs(): Long = when (_state.value.status) {
        Status.RECORDING -> accumulatedMs + (SystemClock.elapsedRealtime() - runStartedAt)
        Status.PAUSED -> accumulatedMs
        Status.IDLE -> 0L
    }

    fun start(sessionId: String): Boolean {
        if (_state.value.status != Status.IDLE) return false
        val session = SessionRepository.get(sessionId) ?: return false
        val dir = SessionRepository.sessionDir(sessionId).apply { mkdirs() }
        var n = session.recordings.size + 1
        while (File(dir, "recording-$n.aac").exists() || File(dir, "recording-$n.m4a").exists()) n++
        val file = File(dir, "recording-$n.aac")

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            // ADTS rather than MP4: every frame stands alone, so the file stays playable even if
            // the app is killed mid-recording (an MP4 without its index can't be opened).
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.setOnErrorListener { _, what, extra -> scope.launch { fail("Recording stopped (error $what/$extra)") } }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            _state.value = State(error = "Couldn't start recording: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        recorder = r
        accumulatedMs = 0
        runStartedAt = SystemClock.elapsedRealtime()
        val rec = Recording(id = newId(), file = file.name, createdAt = System.currentTimeMillis(), durationMs = 0)
        SessionRepository.addRecording(sessionId, rec)
        _state.value = State(Status.RECORDING, sessionId, rec.id)
        startTicker()
        RecordingService.start(app)
        return true
    }

    fun pause() {
        if (_state.value.status != Status.RECORDING) return
        try {
            recorder?.pause()
        } catch (e: IllegalStateException) {
            return
        }
        accumulatedMs += SystemClock.elapsedRealtime() - runStartedAt
        _state.update { it.copy(status = Status.PAUSED, elapsedMs = accumulatedMs, level = 0f) }
    }

    fun resume() {
        if (_state.value.status != Status.PAUSED) return
        try {
            recorder?.resume()
        } catch (e: IllegalStateException) {
            return
        }
        runStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = Status.RECORDING) }
    }

    fun stop() {
        val st = _state.value
        if (st.status == Status.IDLE) return
        val duration = currentOffsetMs()
        ticker?.cancel()
        recorder?.let { r ->
            try {
                r.stop()
            } catch (e: RuntimeException) {
                // Thrown when stop() comes before any audio was captured; the file is just empty.
            }
            r.release()
        }
        recorder = null
        if (st.sessionId != null && st.recId != null) {
            SessionRepository.setRecordingDuration(st.sessionId, st.recId, duration)
            SessionRepository.convertToM4a(st.sessionId, st.recId)
        }
        _state.value = State()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun fail(message: String) {
        stop()
        _state.update { it.copy(error = message) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val amp = if (_state.value.status == Status.RECORDING) {
                    runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                } else {
                    0
                }
                // Map -50 dBFS..0 dBFS onto 0..1.
                val level = if (amp <= 0) 0f else ((20 * log10(amp / 32767f) + 50f) / 50f).coerceIn(0f, 1f)
                _state.update { it.copy(elapsedMs = currentOffsetMs(), level = level) }
                delay(100)
            }
        }
    }
}
