package com.kjwindham.audiocool.summarize

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app going, in the foreground, while [SummaryController] downloads the model or works
 * through summaries with the app closed. It stops itself once the controller has nothing left to do.
 */
class SummaryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(SummaryController.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent?.action == ACTION_CANCEL) SummaryController.cancelDownload()
        if (!started) {
            started = true
            scope.launch { SummaryController.state.collect { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it)) } }
            scope.launch {
                SummaryController.busy.first { !it }
                // A moment's grace: more work may follow straight on.
                delay(2_000)
                SummaryController.busy.first { !it }
                ServiceCompat.stopForeground(this@SummaryService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 caps data-sync services at 6 hours a day; the work just carries on while the app's open.
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: SummaryController.State): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Summaries", NotificationManager.IMPORTANCE_LOW))
        }
        val title = state.sessionId?.let { SessionRepository.get(it)?.title }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        return when {
            state.downloading -> builder
                .setContentTitle("Downloading the summary model")
                .setContentText("${(state.downloadProgress * 100).toInt()}% of ${SummaryModel.SIZE / 1_000_000_000.0} GB".replace(".0 GB", " GB"))
                .setProgress(1000, (state.downloadProgress * 1000).toInt(), false)
                .addAction(0, "Stop", android.app.PendingIntent.getService(
                    this, 0, Intent(this, SummaryService::class.java).setAction(ACTION_CANCEL),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                .build()
            else -> builder
                .setContentTitle(title?.let { "Summarizing “$it”" } ?: "Summarizing")
                .setContentText(if (state.total > 0) "Part ${minOf(state.done + 1, state.total)} of ${state.total}" else "On your phone")
                .setProgress(state.total, state.done, state.total == 0)
                .build()
        }
    }

    companion object {
        private const val TAG = "SummaryService"
        private const val CHANNEL_ID = "summaries"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_CANCEL = "com.kjwindham.audiocool.action.CANCEL_SUMMARY_DOWNLOAD"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SummaryService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background: the work still goes on while the app's alive.
                Log.w(TAG, "Couldn't start in the foreground", e)
            }
        }
    }
}
