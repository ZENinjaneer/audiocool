package com.kjwindham.audiocool.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.backup.BackupController

/** Choose where backups go, back up now, restore after a reinstall, or turn backups off. */
@Composable
fun BackupDialog(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val backup by BackupController.state.collectAsStateWithLifecycle()
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) BackupController.setFolder(uri)
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onMessage("Restoring…")
        BackupController.restore(uri) { restored, error ->
            onMessage(
                when {
                    error != null -> "Couldn't restore: $error"
                    restored == 0 -> "No new sessions in that folder"
                    restored == 1 -> "Restored 1 session"
                    else -> "Restored $restored sessions"
                },
            )
        }
    }
    val folder = backup.folderName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (folder == null) {
                        "Keep a copy of every session (audio, notes and transcript) in a folder you choose, " +
                            "such as Documents. The copy stays on your phone even if you uninstall the app."
                    } else {
                        "Every session is copied to “$folder” automatically. That copy stays on your phone " +
                            "even if you uninstall the app."
                    },
                )
                if (folder != null) {
                    Text(
                        when {
                            backup.running -> "Backing up…"
                            backup.lastBackupAt > 0 -> "Last backup: " + DateUtils.getRelativeTimeSpanString(backup.lastBackupAt)
                            else -> "Not backed up yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                backup.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { chooseFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (folder == null) "Choose backup folder" else "Change folder")
                }
                if (folder != null) {
                    OutlinedButton(onClick = { BackupController.backUpNow() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back up now")
                    }
                }
                OutlinedButton(onClick = { restoreFrom.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from a backup")
                }
                if (folder != null) {
                    TextButton(onClick = { BackupController.turnOff() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn off backup")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
