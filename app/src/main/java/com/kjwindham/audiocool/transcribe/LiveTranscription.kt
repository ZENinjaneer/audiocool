package com.kjwindham.audiocool.transcribe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.data.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.concurrent.thread

/**
 * Transcribes a recording while it's being made, saving each phrase to the session as soon as it's
 * transcribed. It only reads the file MediaRecorder writes, so it can't disturb the recording.
 */
object LiveTranscription {
    data class State(val sessionId: String? = null, val recId: String? = null, val speaking: Boolean = false) {
        val active: Boolean get() = recId != null
    }

    private const val TAG = "LiveTranscription"
    private const val VAD_ASSET = "silero_vad.onnx"
    private const val DENOISER_ASSET = "gtcrn_simple.onnx"

    // It only has to keep up with speech, and the phone is busy recording: use fewer threads than
    // transcription after the fact.
    private const val THREADS = 2

    /** How long it may keep catching up after recording stops before the background service takes over. */
    private const val CATCH_UP_MS = 15_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private class Run(val sessionId: String, val recId: String, val source: AdtsTailSource) {
        @Volatile
        var transcriber: LiveTranscriber? = null

        @Volatile
        var handedOff = false
    }

    @Volatile
    private var current: Run? = null
    private val main = Handler(Looper.getMainLooper())

    // The loaded speech model, shared with dictation; the lock keeps it from being freed mid-use.
    private val recognizerLock = Any()
    private var sharedRecognizer: OfflineRecognizer? = null

    /** Runs [block] with the speech model live transcription has loaded; null if it hasn't one. */
    fun <T> withRecognizer(block: (OfflineRecognizer) -> T): T? = synchronized(recognizerLock) { sharedRecognizer?.let(block) }

    /** Starts transcribing [file] as it's recorded, if the model is downloaded and the user wants it. */
    fun start(context: Context, sessionId: String, recId: String, file: File) {
        if (current != null || !TranscriptionController.liveEnabled) return
        val app = context.applicationContext
        val run = Run(sessionId, recId, AdtsTailSource(file))
        current = run
        TranscriptionController.liveStarted(sessionId, recId)
        _state.value = State(sessionId, recId)
        thread(name = "live-transcription") { work(app, run) }
    }

    /** The recording is finished. Returns false if live transcription isn't covering it. */
    fun recordingStopped(recId: String): Boolean {
        val run = current?.takeIf { it.recId == recId } ?: return false
        run.source.writerFinished()
        // Normally it's done within a second or two; if it fell behind, hand the rest over.
        main.postDelayed({
            if (current === run) {
                run.handedOff = true
                run.transcriber?.stopRequested = true
                run.source.stop()
            }
        }, CATCH_UP_MS)
        return true
    }

    private fun work(context: Context, run: Run) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioCool:live")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        var recognizer: OfflineRecognizer? = null
        var vad: Vad? = null
        var denoiser: OnlineSpeechDenoiser? = null
        val result = try {
            // Fail fast, before loading the speech engine, if the model's files have gone missing.
            check(SpeechModel.isReady(context)) { "The speech model isn't downloaded" }
            recognizer = OfflineRecognizer(null, SpeechModel.recognizerConfig(context, THREADS))
            synchronized(recognizerLock) { sharedRecognizer = recognizer }
            vad = Vad(context.assets, Transcriber.vadConfig(VAD_ASSET))
            denoiser = OnlineSpeechDenoiser(context.assets, Transcriber.denoiserConfig(DENOISER_ASSET))
            val live = LiveTranscriber(
                run.source,
                recognizer,
                vad,
                onSegment = { SessionRepository.appendTranscriptSegment(run.sessionId, run.recId, it, SpeechModel.ID) },
                onSpeaking = { speaking -> _state.update { if (it.recId == run.recId) it.copy(speaking = speaking) else it } },
                denoiser = denoiser,
            )
            run.transcriber = live
            if (run.handedOff) live.stopRequested = true
            live.run()
        } catch (e: Throwable) {
            run.source.close()
            LiveTranscriber.Result.Stopped(emptyList(), 0L, e)
        } finally {
            synchronized(recognizerLock) { sharedRecognizer = null }
            recognizer?.release()
            vad?.release()
            denoiser?.release()
            if (wakeLock.isHeld) wakeLock.release()
        }
        finish(run, result)
    }

    private fun finish(run: Run, result: LiveTranscriber.Result) {
        when (result) {
            is LiveTranscriber.Result.Finished ->
                SessionRepository.setTranscript(run.sessionId, run.recId, result.segments, SpeechModel.ID)
            is LiveTranscriber.Result.Stopped -> {
                result.error?.let { Log.w(TAG, "Live transcription stopped early", it) }
                // Finish the rest in the background (it waits until the recording has ended).
                val from = SessionRepository.get(run.sessionId)?.recording(run.recId)?.transcript?.lastOrNull()?.endMs ?: 0L
                TranscriptionController.enqueue(run.sessionId, listOf(run.recId), fromMs = from)
            }
        }
        TranscriptionController.liveFinished()
        current = null
        _state.value = State()
    }
}
