package com.kjwindham.audiocool.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.ui.CaptureActivity
import com.kjwindham.audiocool.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The optional Record button for the lock screen: a quiet notification, there whenever nothing is
 * recording, that starts a recording without unlocking. (The quick settings tile can't: Android asks
 * to unlock before a tile opens anything, and won't let one turn the microphone on behind the lock.)
 * Tapping the notification starts recording with the capture screen open, ready for photos; its
 * Record button starts recording and leaves the lock screen as it is.
 */
object QuickRecord {
    private const val CHANNEL_ID = "quick_record"
    private const val NOTIFICATION_ID = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    fun init(context: Context) {
        val app = context.applicationContext
        // Back whenever recording stops and gone whenever it starts, from wherever that happens.
        watcher?.cancel()
        watcher = scope.launch {
            RecorderController.state.map { it.status == RecorderController.Status.IDLE }.distinctUntilChanged().collect { update(app) }
        }
    }

    fun isEnabled(context: Context) = Prefs(context).quickRecord

    fun setEnabled(context: Context, on: Boolean) {
        Prefs(context).quickRecord = on
        // Woken at startup only while it's on, to put the notification back after a restart or an update.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, QuickRecordRestorer::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        update(context)
    }

    /** Shows or hides the notification to match the setting and the recorder. */
    fun update(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!isEnabled(context) || !micAllowed || RecorderController.state.value.status != RecorderController.Status.IDLE) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // Less than default importance and Android keeps it off the lock screen. Never a sound, though.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Record button", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Starts a recording from the lock screen without unlocking"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val startWithCamera = PendingIntent.getActivity(
            context,
            CaptureActivity.ACTION_START.hashCode(),
            Intent(context, CaptureActivity::class.java).setAction(CaptureActivity.ACTION_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Ready to record")
                .setContentText("Tap to start, with the camera ready")
                .setContentIntent(startWithCamera)
                // A button rather than a tap, so it works even where tapping a notification on the lock screen asks to unlock.
                .addAction(0, "● Record", RecordingService.recordIntent(context))
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build(),
        )
    }
}

/** Puts [QuickRecord]'s notification back after the phone restarts or the app is updated. Enabled only while it's on. */
class QuickRecordRestorer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) QuickRecord.update(context)
    }
}
