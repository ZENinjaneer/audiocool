package com.kjwindham.audiocool.audio

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kjwindham.audiocool.ui.CaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A quick settings tile that starts a recording (in a new session) and stops it. Starting goes through
 * [CaptureActivity], since a tile may not turn the microphone on by itself; on the lock screen Android
 * asks to unlock before opening it. (The lock screen's own way to start is [QuickRecord]'s notification.)
 */
class RecordTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch = scope.launch {
            RecorderController.state.map { it.status }.distinctUntilChanged().collect { update(it) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form, for Android 13 and older
    override fun onClick() {
        super.onClick()
        if (RecorderController.state.value.status != RecorderController.Status.IDLE) {
            RecorderController.stop()
            return
        }
        val intent = Intent(this, CaptureActivity::class.java)
            .setAction(CaptureActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(status: RecorderController.Status) {
        val tile = qsTile ?: return
        val recording = status != RecorderController.Status.IDLE
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "Stop recording" else "Record"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "AudioCool"
        tile.updateTile()
    }
}
