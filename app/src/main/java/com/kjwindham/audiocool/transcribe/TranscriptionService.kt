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
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.MainActivity
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.transcribe.TranscriptionController.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

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
            while (true) {
                val job = TranscriptionController.takeNext() ?: break
                val error = try {
                    process(job)
                    null
                } catch (e: CancellationException) {
                    if (!coroutineContext.isActive) throw e // the service is shutting down
                    null // the user stopped this one
                } catch (e: Throwable) {
                    Log.e(TAG, "Transcription failed", e)
                    "Couldn't transcribe: ${e.message ?: e.javaClass.simpleName}"
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
                if (scope.isActive && TranscriptionController.state.value.queue.isNotEmpty()) {
                    worker = scope.launch { work() }
                } else {
                    ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun process(job: TranscriptionController.Job) {
        val rec = SessionRepository.get(job.sessionId)?.recording(job.recId) ?: return
        if (rec.durationMs <= 0) return // still recording, or nothing was captured
        val cancelled = { TranscriptionController.isCancelRequested || !scope.isActive }

        if (!SpeechModel.isReady(this)) {
            TranscriptionController.report(Phase.DOWNLOADING_MODEL, 0f)
            notifyProgress(force = true)
            SpeechModel.download(this, cancelled) { done, total ->
                TranscriptionController.report(Phase.DOWNLOADING_MODEL, done.toFloat() / total)
                notifyProgress()
            }
            TranscriptionController.modelDownloaded()
        }

        TranscriptionController.report(Phase.TRANSCRIBING, 0f)
        notifyProgress(force = true)
        val engine = recognizer
            ?: OfflineRecognizer(null, Transcriber.recognizerConfig(SpeechModel.dir(this), THREADS)).also { recognizer = it }
        // Look the file up now, not before the (possibly long) model download: by now the recording
        // may have been converted from .aac to .m4a.
        val latest = SessionRepository.get(job.sessionId)?.recording(job.recId) ?: return
        val vad = Vad(assets, Transcriber.vadConfig(VAD_ASSET))
        try {
            val transcriber = Transcriber(engine, vad)
            AudioDecoder(SessionRepository.audioFile(job.sessionId, latest), Transcriber.SAMPLE_RATE).decode(
                isCancelled = cancelled,
                onProgress = {
                    TranscriptionController.report(Phase.TRANSCRIBING, it)
                    notifyProgress()
                },
                onChunk = { transcriber.accept(it) },
            )
            SessionRepository.setTranscript(job.sessionId, job.recId, transcriber.finish())
        } finally {
            vad.release()
        }
    }

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
        const val THREADS = 4
    }
}
