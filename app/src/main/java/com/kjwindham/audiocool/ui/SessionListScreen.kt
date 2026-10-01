package com.kjwindham.audiocool.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.defaultSessionTitle
import com.kjwindham.audiocool.util.formatDate
import com.kjwindham.audiocool.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(sessions: List<Session>, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    val rec by RecorderController.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    val sorted = remember(sessions) { sessions.sortedByDescending { it.updatedAt } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AudioCool") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                // Material3 hides the FAB's text from accessibility, so the icon carries the label.
                icon = { Icon(Icons.Filled.Add, contentDescription = "New session") },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeId = rec.sessionId
            if (rec.status != RecorderController.Status.IDLE && activeId != null) {
                item(key = "recording") {
                    RecordingBanner(
                        title = sessions.firstOrNull { it.id == activeId }?.title.orEmpty(),
                        elapsedMs = rec.elapsedMs,
                        paused = rec.status == RecorderController.Status.PAUSED,
                        onClick = { onOpen(activeId) },
                    )
                }
            }
            if (sorted.isEmpty()) {
                item(key = "empty") { EmptyState() }
            }
            items(sorted, key = { it.id }) { s ->
                SessionCard(s, onClick = { onOpen(s.id) }, onRename = { renaming = s }, onDelete = { deleting = s })
            }
        }
    }

    renaming?.let { s ->
        TextInputDialog(
            title = "Rename session",
            initial = s.title,
            onConfirm = {
                SessionRepository.rename(s.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete session?",
            message = "“${s.title}” and its recordings will be permanently deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                deleteSession(s.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: Session, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardDefaults.shape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    summary(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

private fun summary(s: Session): String {
    val parts = mutableListOf<String>()
    // A session still named after its start time doesn't need the date twice.
    if (s.title != defaultSessionTitle(s.createdAt)) parts += formatDate(s.createdAt)
    if (s.recordings.isNotEmpty()) parts += "${formatTime(s.totalDurationMs)} audio"
    parts += when (s.notes.size) {
        0 -> "no notes"
        1 -> "1 note"
        else -> "${s.notes.size} notes"
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingBanner(title: String, elapsedMs: Long, paused: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(RecordRed))
            Spacer(Modifier.width(12.dp))
            Text(
                "${if (paused) "Paused" else "Recording"} · ${formatTime(elapsedMs)} · $title",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No sessions yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Start a session, hit record, and type notes as you listen. Each note is linked to that " +
                "moment in the audio. Tap it later to hear what was being said.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
