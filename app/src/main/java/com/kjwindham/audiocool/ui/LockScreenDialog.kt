package com.kjwindham.audiocool.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.R
import com.kjwindham.audiocool.audio.QuickRecord
import com.kjwindham.audiocool.audio.RecordTileService

/** Explains the lock screen controls, and sets up the parts that need a yes: the Record button, the tile and the camera. */
@Composable
fun LockScreenDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    var tileMessage by remember { mutableStateOf<String?>(null) }
    var quickRecord by remember { mutableStateOf(QuickRecord.isEnabled(context)) }
    var quickRecordMessage by remember { mutableStateOf<String?>(null) }
    val askForQuickRecord = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // On either way: the notification appears once it's allowed.
        QuickRecord.setEnabled(context, true)
        quickRecord = true
        quickRecordMessage = if (granted.values.all { it }) null else "It shows once AudioCool may use the microphone and post notifications."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock screen controls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "While recording, the notification on your lock screen has ★ Mark, Pause and Stop, and they work " +
                        "without unlocking. Tap the notification for the camera and spoken notes; that screen never shows your notes.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Record button on the lock screen")
                        Text(
                            "A quiet notification, there while you're not recording, that starts a recording without unlocking.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = quickRecord,
                        onCheckedChange = { on ->
                            val missing = quickRecordPermissions().filter {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (on && missing.isNotEmpty()) {
                                askForQuickRecord.launch(missing.toTypedArray())
                            } else {
                                QuickRecord.setEnabled(context, on)
                                quickRecord = on
                                quickRecordMessage = null
                            }
                        },
                    )
                }
                quickRecordMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "The Record tile in your quick settings (swipe down from the top) starts and stops recording too, " +
                        "but on the lock screen Android asks you to unlock before it starts.",
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = { addTile(context) { tileMessage = it } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add the Record tile")
                    }
                } else {
                    Text("Open your quick settings, tap the pencil to edit them, and drag in AudioCool's Record tile.")
                }
                tileMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (cameraAllowed) {
                    Text("The camera is allowed, so you can take photos from the lock screen.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = { askCamera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow the camera")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun quickRecordPermissions() = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun addTile(context: Context, onResult: (String) -> Unit) {
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, RecordTileService::class.java),
        "Record",
        Icon.createWithResource(context, R.drawable.ic_stat_mic),
        context.mainExecutor,
    ) { result ->
        onResult(
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                -> "The Record tile is in your quick settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Not added. You can add it later by editing your quick settings."
                else -> "Couldn't add it from here; add it by editing your quick settings."
            },
        )
    }
}
