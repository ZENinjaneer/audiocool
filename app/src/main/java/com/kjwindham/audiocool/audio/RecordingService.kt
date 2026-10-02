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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ui.CaptureActivity
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
 *
 * Its notification works from the lock screen without unlocking: ★ marks the moment, and Pause and Stop
 * do what they say. Tapping it opens [CaptureActivity] over the lock screen, for photos and spoken notes.
 * (A button that opened a screen would make Android ask to unlock first; tapping the notification doesn't.)
 * [QuickRecord]'s Record button starts it too, recording into a new session.
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
            ACTION_MARK -> RecorderController.mark()
            ACTION_RECORD -> record()
            // Started via startForegroundService(): must go foreground right away.
            else -> goForeground()
        }
        if (!watching) {
            watching = true
            scope.launch {
                RecorderController.state.map { it.status to it.markedAtMs }.distinctUntilChanged().collect { (status, _) ->
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

    private fun goForeground() = ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        buildNotification(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
    )

    /** The Record button: records into a new session without opening anything, so it works on the lock screen. */
    private fun record() {
        if (RecorderController.state.value.status != RecorderController.Status.IDLE) return
        // Foreground before the microphone opens, so not a moment of it is silenced. Android refuses
        // without the microphone permission; then the button just stays where it was.
        val foreground = try {
            goForeground()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't start recording from the notification", e)
            false
        }
        if (!foreground || RecorderController.startNewSession() == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // The old channel was "low" importance, which Android keeps off the lock screen, and a
            // channel's importance can't be raised later: replace it. No sound or vibration either way.
            nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shows while recording, with controls that work from the lock screen"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val st = RecorderController.state.value
        val title = st.sessionId?.let { SessionRepository.get(it)?.title } ?: getString(R.string.app_name)
        val paused = st.status == RecorderController.Status.PAUSED
        val marked = st.markedAtMs?.let { " · ★ at ${formatTime(it)}" }.orEmpty()
        fun build(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (paused) "Paused" else "Recording")
            .setContentText(text + marked)
            .setSubText("Tap for camera")
            // Opens the capture screen, which goes on to the session when the phone is unlocked.
            .setContentIntent(capture(CaptureActivity.ACTION_OPEN))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(!paused)
            .setShowWhen(!paused)
            .setWhen(System.currentTimeMillis() - RecorderController.currentOffsetMs())
            .addAction(0, "★ Mark", action(ACTION_MARK))
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(0, "Stop", action(ACTION_STOP))
        // On a lock screen that hides notification content: the same controls, without the session's name.
        return build(if (paused) "$title · ${formatTime(st.elapsedMs)}" else title)
            .setPublicVersion(build(if (paused) formatTime(st.elapsedMs) else getString(R.string.app_name)).build())
            .build()
    }

    private fun capture(action: String): PendingIntent = PendingIntent.getActivity(
        this,
        action.hashCode(),
        Intent(this, CaptureActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, RecordingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_controls"
        private const val OLD_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.kjwindham.audiocool.action.PAUSE"
        private const val ACTION_RESUME = "com.kjwindham.audiocool.action.RESUME"
        private const val ACTION_STOP = "com.kjwindham.audiocool.action.STOP"
        private const val ACTION_MARK = "com.kjwindham.audiocool.action.MARK"
        private const val ACTION_RECORD = "com.kjwindham.audiocool.action.RECORD"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        /**
         * Starts a recording into a new session; for a notification button. A plain service start, not
         * a foreground one: a notification's button may start the microphone this way, and if Android
         * says no anyway the service can simply stop instead of having broken a promise to go foreground.
         */
        fun recordIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            ACTION_RECORD.hashCode(),
            Intent(context, RecordingService::class.java).setAction(ACTION_RECORD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
