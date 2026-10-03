package com.kjwindham.audiocool.transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.MainActivity
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.speakers.KnownVoices
import com.kjwindham.audiocool.speakers.VoiceModel
import com.kjwindham.audiocool.speakers.VoicePrints
import com.kjwindham.audiocool.speakers.Voices
import com.kjwindham.audiocool.speakers.WhoSaidWhat
import com.kjwindham.audiocool.transcribe.TranscriptionController.Phase
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Works through [TranscriptionController]'s queue in the foreground (so it keeps going with the app
 * closed and the screen off): downloads the model if needed, then decodes and transcribes each recording.
 */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var recognizer: OfflineRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent?.action == ACTION_STOP) TranscriptionController.cancelAll()
        if (worker?.isActive != true) worker = scope.launch { work() }
        return START_NOT_STICKY
    }

    // Android 15 caps data-sync services at 6 hours a day; stop cleanly if that's reached.
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun work() {
        acquireWakeLock()
        try {
            // The model can download during a recording; only transcribing waits for it to end.
            if (TranscriptionController.needsModel && !SpeechModel.isReady(this)) {
                val error = try {
                    ensureModel()
                    null
                } catch (e: CancellationException) {
                    if (!coroutineContext.isActive) throw e
                    null // the user stopped it
                } catch (e: Throwable) {
                    Log.e(TAG, "Model download failed", e)
                    "Couldn't download the speech model: ${e.message ?: e.javaClass.simpleName}"
                }
                TranscriptionController.downloadFinished(error)
                notifyProgress(force = true)
            }
            while (true) {
                // Leave the queue alone while recording; it resumes when the recording stops.
                if (recording()) break
                val job = TranscriptionController.takeNext() ?: break
                val error = try {
                    process(job)
                    null
                } catch (e: CancellationException) {
                    if (!coroutineContext.isActive) throw e // the service is shutting down
                    null // the user stopped this one
                } catch (e: Throwable) {
                    Log.e(TAG, if (job.speakers) "Finding who said what failed" else "Transcription failed", e)
                    (if (job.speakers) "Couldn't find who said what: " else "Couldn't transcribe: ") + (e.message ?: e.javaClass.simpleName)
                }
                TranscriptionController.finished(error)
                notifyProgress(force = true)
            }
        } finally {
            recognizer?.release()
            recognizer = null
            wakeLock?.let { if (it.isHeld) it.release() }
            withContext(NonCancellable + Dispatchers.Main) {
                // Something may have been queued while this worker was finishing; its start request
                // found the worker still running, so pick it up here rather than stopping.
                if (scope.isActive && !recording() && TranscriptionController.state.value.queue.isNotEmpty()) {
                    worker = scope.launch { work() }
                } else {
                    ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private val cancelled = { TranscriptionController.isCancelRequested || !scope.isActive }

    /** Downloads the speech model if it isn't here yet. */
    private fun ensureModel() {
        if (SpeechModel.isReady(this)) return
        TranscriptionController.report(Phase.DOWNLOADING_MODEL, 0f)
        notifyProgress(force = true)
        SpeechModel.download(this, cancelled) { done, total ->
            TranscriptionController.report(Phase.DOWNLOADING_MODEL, done.toFloat() / total)
            notifyProgress()
        }
        TranscriptionController.modelDownloaded()
        SpeechModel.removeOldModels(this)
    }

    private fun process(job: TranscriptionController.Job) {
        if (job.speakers) return findSpeakers(job)
        if (SessionRepository.get(job.sessionId)?.recording(job.recId) == null) return
        ensureModel()

        TranscriptionController.report(Phase.TRANSCRIBING, 0f)
        notifyProgress(force = true)
        val engine = recognizer
            ?: OfflineRecognizer(null, SpeechModel.recognizerConfig(this, THREADS)).also { recognizer = it }
        // Look the file up now, not before the (possibly long) model download: by now the recording
        // may have been converted from .aac to .m4a.
        val latest = SessionRepository.get(job.sessionId)?.recording(job.recId) ?: return
        val vad = Vad(assets, Transcriber.vadConfig(VAD_ASSET))
        val denoiser = OnlineSpeechDenoiser(assets, Transcriber.denoiserConfig(DENOISER_ASSET))
        try {
            // A job can pick up where live transcription left off: skip what's already transcribed.
            val transcriber = Transcriber(engine, vad, offsetMs = job.fromMs, denoiser = denoiser)
            var skip = job.fromMs * Transcriber.SAMPLE_RATE / 1000
            AudioDecoder(SessionRepository.audioFile(job.sessionId, latest), Transcriber.SAMPLE_RATE).decode(
                isCancelled = cancelled,
                onProgress = {
                    TranscriptionController.report(Phase.TRANSCRIBING, it)
                    notifyProgress()
                },
                onChunk = { chunk ->
                    if (skip >= chunk.size) {
                        skip -= chunk.size
                    } else {
                        transcriber.accept(if (skip > 0) chunk.copyOfRange(skip.toInt(), chunk.size) else chunk)
                        skip = 0
                    }
                },
            )
            val earlier = if (job.fromMs > 0) latest.transcript.orEmpty().filter { it.endMs <= job.fromMs } else emptyList()
            SessionRepository.setTranscript(job.sessionId, job.recId, earlier + transcriber.finish(), SpeechModel.ID)
        } finally {
            vad.release()
            denoiser.release()
        }
    }

    /**
     * Who said what in a recording: the audio a chunk at a time through [WhoSaidWhat], then its voices
     * matched with those the session has (from its other recordings) and the ones you've named before.
     */
    private fun findSpeakers(job: TranscriptionController.Job) {
        val rec = SessionRepository.get(job.sessionId)?.recording(job.recId) ?: return
        if (!VoiceModel.isReady(this)) {
            TranscriptionController.report(Phase.DOWNLOADING_VOICE_MODEL, 0f)
            notifyProgress(force = true)
            VoiceModel.download(this, cancelled) { done, total ->
                TranscriptionController.report(Phase.DOWNLOADING_VOICE_MODEL, done.toFloat() / total)
                notifyProgress()
            }
        }
        TranscriptionController.report(Phase.FINDING_SPEAKERS, 0f)
        notifyProgress(force = true)
        val diarization = OfflineSpeakerDiarization(null, VoiceModel.diarizationConfig(this, THREADS))
        val extractor = SpeakerEmbeddingExtractor(null, VoiceModel.embeddingConfig(this, THREADS))
        try {
            val who = WhoSaidWhat(diarization, extractor, VoiceModel.SAME_VOICE)
            val chunkSize = (WhoSaidWhat.CHUNK_MS * WhoSaidWhat.SAMPLE_RATE / 1000).toInt()
            var chunk = FloatArray(chunkSize)
            var filled = 0
            var chunkStartMs = 0L
            fun flush() {
                if (filled == 0) return
                val samples = if (filled == chunk.size) chunk else chunk.copyOf(filled)
                val chunkMs = filled * 1000L / WhoSaidWhat.SAMPLE_RATE
                who.chunk(samples, chunkStartMs)
                chunkStartMs += chunkMs
                // How far through the recording the voices have been found (decoding is quick by comparison).
                TranscriptionController.report(Phase.FINDING_SPEAKERS, (chunkStartMs.toFloat() / maxOf(1L, rec.durationMs)).coerceIn(0f, 1f))
                notifyProgress()
                filled = 0
                chunk = FloatArray(chunkSize)
            }
            val latest = SessionRepository.get(job.sessionId)?.recording(job.recId) ?: return
            AudioDecoder(SessionRepository.audioFile(job.sessionId, latest), WhoSaidWhat.SAMPLE_RATE).decode(
                isCancelled = cancelled,
                onProgress = {},
                onChunk = { samples ->
                    var at = 0
                    while (at < samples.size) {
                        val n = minOf(samples.size - at, chunk.size - filled)
                        samples.copyInto(chunk, filled, at, at + n)
                        filled += n
                        at += n
                        if (filled == chunk.size) flush()
                    }
                },
            )
            if (cancelled()) return
            flush()
            val (turns, prints) = who.result()
            val session = SessionRepository.get(job.sessionId) ?: return
            // A run again: this recording's old turns don't count when matching.
            val others = session.copy(recordings = session.recordings.map { if (it.id == job.recId) it.copy(speakers = null) else it })
            val kept = others.voices.filter { v -> others.recordings.any { r -> r.speakers.orEmpty().any { it.voice == v.id } } || v.name != null }
            val matched = Voices.match(prints, kept, VoicePrints.of(job.sessionId), KnownVoices.voices.value, VoiceModel.SAME_VOICE, VoiceModel.KNOWN_VOICE)
            SessionRepository.setSpeakers(job.sessionId, job.recId, turns.map { it.copy(voice = matched.ids[it.voice]) }, matched.voices)
            val heard = SessionRepository.get(job.sessionId)?.voices.orEmpty().map { it.id }.toSet()
            VoicePrints.set(job.sessionId, matched.prints.filterKeys { it in heard })
            Log.i(TAG, "Found ${matched.ids.distinct().size} voices in ${job.recId}: ${turns.size} turns")
        } finally {
            diarization.release()
            extractor.release()
        }
    }

    private fun recording() = RecorderController.state.value.status != RecorderController.Status.IDLE

    private fun acquireWakeLock() {
        // A foreground service alone doesn't keep the CPU running once the screen turns off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioCool:transcribe")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifiedAt < 1_000) return
        lastNotifiedAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW))
        }
        val s = TranscriptionController.state.value
        val session = s.current?.let { SessionRepository.get(it.sessionId) }
        val percent = (s.progress * 100).toInt()
        val title = when (s.phase) {
            Phase.DOWNLOADING_MODEL -> "Downloading speech model · $percent%"
            Phase.TRANSCRIBING -> "Transcribing · $percent%"
            Phase.DOWNLOADING_VOICE_MODEL -> "Downloading voice model · $percent%"
            Phase.FINDING_SPEAKERS -> "Finding who said what · $percent%"
            Phase.IDLE -> "Transcribing"
        }
        val detail = listOfNotNull(session?.title, s.queue.size.takeIf { it > 0 }?.let { "$it more queued" })
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, session?.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(detail.joinToString(" · "))
            .setProgress(100, percent, s.phase == Phase.IDLE)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    private companion object {
        const val TAG = "TranscriptionService"
        const val CHANNEL_ID = "transcription"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.kjwindham.audiocool.action.STOP_TRANSCRIBING"
        const val VAD_ASSET = "silero_vad.onnx"
        const val DENOISER_ASSET = "gtcrn_simple.onnx"
        const val THREADS = 4
    }
}
