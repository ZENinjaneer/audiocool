package com.kjwindham.audiocool.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.MainActivity
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while recording so the microphone keeps working with the
 * screen off or another app open. The recorder itself lives in [RecorderController].
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> RecorderController.pause()
            ACTION_RESUME -> RecorderController.resume()
            ACTION_STOP -> RecorderController.stop()
            // Started via startForegroundService(): must go foreground right away.
            else -> ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }
        if (!watching) {
            watching = true
            scope.launch {
                RecorderController.state.map { it.status }.distinctUntilChanged().collect { status ->
                    if (status == RecorderController.Status.IDLE) {
                        ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
        val st = RecorderController.state.value
        val title = st.sessionId?.let { SessionRepository.get(it)?.title } ?: getString(R.string.app_name)
        val paused = st.status == RecorderController.Status.PAUSED
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, st.sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (paused) "Paused" else "Recording")
            .setContentText(if (paused) "$title · ${formatTime(st.elapsedMs)}" else title)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(!paused)
            .setShowWhen(!paused)
            .setWhen(System.currentTimeMillis() - RecorderController.currentOffsetMs())
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "Stop", action(ACTION_STOP))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, RecordingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.kjwindham.audiocool.action.PAUSE"
        private const val ACTION_RESUME = "com.kjwindham.audiocool.action.RESUME"
        private const val ACTION_STOP = "com.kjwindham.audiocool.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }
}
