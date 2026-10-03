package com.kjwindham.audiocool.transcribe

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The transcription queue, which also finds who said what. [TranscriptionService] works through it in
 * the background, one at a time (both keep the phone busy).
 */
object TranscriptionController {
    enum class Phase { IDLE, DOWNLOADING_MODEL, TRANSCRIBING, DOWNLOADING_VOICE_MODEL, FINDING_SPEAKERS }

    /**
     * Transcribe a recording, from [fromMs] on (earlier parts are already transcribed); or, with
     * [speakers], find who spoke when in it.
     */
    data class Job(val sessionId: String, val recId: String, val fromMs: Long = 0, val speakers: Boolean = false)

    data class State(
        val queue: List<Job> = emptyList(),
        val current: Job? = null,
        val phase: Phase = Phase.IDLE,
        /** 0..1 through the current phase. */
        val progress: Float = 0f,
        val modelReady: Boolean = false,
        val error: String? = null,
    ) {
        /** Whether the recording is waiting to be transcribed, or being transcribed. */
        fun isPending(sessionId: String, recId: String) =
            (current?.let { !it.speakers && it.sessionId == sessionId && it.recId == recId } ?: false) ||
                queue.any { !it.speakers && it.sessionId == sessionId && it.recId == recId }
        fun isBusyWith(sessionId: String) = current?.sessionId == sessionId || queue.any { it.sessionId == sessionId }

        /** Whether who said what is being worked out for the session, or waiting to be. */
        fun isFindingSpeakers(sessionId: String) =
            current?.let { it.speakers && it.sessionId == sessionId } == true || queue.any { it.speakers && it.sessionId == sessionId }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: Prefs

    @Volatile
    private var cancelRequested = false

    // The user asked for the speech model, e.g. to transcribe the recording in progress live.
    @Volatile
    private var wantModel = false
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        // The saved queue includes a job that was interrupted, e.g. by the app being killed.
        val queue = decode(prefs.transcriptionQueue).toMutableList()
        // Live transcription that never finished (the app was killed while recording): pick it up
        // from where it got to.
        prefs.liveRecording.split(":").takeIf { it.size == 2 }?.let { (sessionId, recId) ->
            if (queue.none { it.sessionId == sessionId && it.recId == recId }) {
                val from = SessionRepository.get(sessionId)?.recording(recId)?.transcript?.lastOrNull()?.endMs ?: 0L
                queue += Job(sessionId, recId, from)
            }
            prefs.liveRecording = ""
        }
        _state.value = State(queue = queue, modelReady = SpeechModel.isReady(app))
        save()
    }

    /** Transcribe while recording: on when automatic transcription is on and the model is downloaded. */
    val liveEnabled: Boolean get() = prefs.autoTranscribe && _state.value.modelReady

    internal fun liveStarted(sessionId: String, recId: String) {
        prefs.liveRecording = "$sessionId:$recId"
    }

    internal fun liveFinished() {
        prefs.liveRecording = ""
    }

    var autoTranscribe: Boolean
        get() = prefs.autoTranscribe
        set(value) {
            prefs.autoTranscribe = value
        }

    fun enqueue(sessionId: String, recIds: List<String>, fromMs: Long = 0) {
        _state.update { s ->
            val add = recIds.filterNot { s.isPending(sessionId, it) }.map { Job(sessionId, it, fromMs) }
            s.copy(queue = s.queue + add, error = null)
        }
        save()
        startWorker()
    }

    /** Finds who said what in the session's [recIds] (after any transcribing queued for them). */
    fun findSpeakers(sessionId: String, recIds: List<String>) {
        _state.update { s ->
            val pending = (listOfNotNull(s.current) + s.queue).filter { it.speakers && it.sessionId == sessionId }.map { it.recId }.toSet()
            s.copy(queue = s.queue + recIds.filterNot { it in pending }.map { Job(sessionId, it, speakers = true) }, error = null)
        }
        save()
        startWorker()
    }

    /**
     * A recording just stopped: transcribe it automatically once the model has been set up (or is on
     * its way), and in a session sorted by voice, find who spoke in it too.
     */
    fun onRecordingFinished(sessionId: String, recId: String) {
        if (prefs.autoTranscribe && (_state.value.modelReady || wantModel)) enqueue(sessionId, listOf(recId))
        if (SessionRepository.get(sessionId)?.voices?.isNotEmpty() == true) findSpeakers(sessionId, listOf(recId))
    }

    /**
     * Downloads the speech model now. Unlike transcribing, this doesn't wait for a recording to end:
     * once it's done, the recording in progress is transcribed live, catching up from its start.
     */
    fun downloadModel() {
        if (_state.value.modelReady) return
        wantModel = true
        startWorker()
    }

    /** Whether something is waiting for the speech model to be downloaded (finding who said what doesn't need it). */
    internal val needsModel: Boolean
        get() = _state.value.let { !it.modelReady && (wantModel || it.queue.any { j -> !j.speakers } || it.current?.speakers == false) }

    /** Drops the session's queued recordings, and stops the one in progress if it's from this session. */
    fun cancel(sessionId: String) {
        _state.update { s -> s.copy(queue = s.queue.filterNot { it.sessionId == sessionId }) }
        if (_state.value.current?.sessionId == sessionId) cancelRequested = true
        save()
    }

    fun cancelAll() {
        wantModel = false
        _state.update { it.copy(queue = emptyList()) }
        if (_state.value.current != null || _state.value.phase == Phase.DOWNLOADING_MODEL) cancelRequested = true
        save()
    }

    /** Starts the background worker if there's work. Safe to call any time, e.g. when the app opens. */
    fun startWorker() {
        val s = _state.value
        // Transcribing waits while recording (the phone's busy enough; RecorderController calls this
        // again when it stops), but downloading the model doesn't have to.
        val idle = RecorderController.state.value.status == RecorderController.Status.IDLE
        if (!(idle && (s.queue.isNotEmpty() || s.current != null)) && !needsModel) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, TranscriptionService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ won't start it while the app is in the background; it's retried when the app opens.
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // Used by TranscriptionService.

    internal val isCancelRequested: Boolean get() = cancelRequested

    internal fun takeNext(): Job? {
        var next: Job? = null
        _state.update { s ->
            next = s.queue.firstOrNull()
            s.copy(queue = s.queue.drop(1), current = next, progress = 0f)
        }
        save()
        return next
    }

    internal fun report(phase: Phase, progress: Float) = _state.update { it.copy(phase = phase, progress = progress) }

    internal fun modelDownloaded() {
        wantModel = false
        _state.update { it.copy(modelReady = true) }
        // On the main thread, in step with the recording starting and stopping.
        main.post { RecorderController.modelReady() }
    }

    /** A download on its own (not for a queued recording) ended; [error] if it failed. */
    internal fun downloadFinished(error: String?) {
        cancelRequested = false
        _state.update { it.copy(phase = Phase.IDLE, progress = 0f, error = error ?: it.error) }
    }

    internal fun finished(error: String?) {
        cancelRequested = false
        _state.update { it.copy(current = null, phase = Phase.IDLE, progress = 0f, error = error ?: it.error) }
        save()
    }

    private fun save() {
        val s = _state.value
        prefs.transcriptionQueue = (listOfNotNull(s.current) + s.queue)
            .joinToString(",") { "${it.sessionId}:${it.recId}:${it.fromMs}" + if (it.speakers) ":s" else "" }
    }

    private fun decode(saved: String): List<Job> = saved.split(",").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size < 2) null else Job(parts[0], parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L, parts.getOrNull(3) == "s")
    }
}
