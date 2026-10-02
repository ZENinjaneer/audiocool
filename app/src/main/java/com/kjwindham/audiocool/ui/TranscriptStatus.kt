package com.kjwindham.audiocool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.search.findTerms
import com.kjwindham.audiocool.search.searchTerms
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.timeLabel

/**
 * What's happening with the session's transcript, shown above its timeline: transcribing live, on
 * the desktop or in the background, waiting to be done, or made with the older model.
 */
@Composable
fun TranscriptStatus(
    session: Session,
    transcription: TranscriptionController.State,
    live: LiveTranscription.State,
    desktopProgress: DesktopSync.Progress?,
    recordingHere: Boolean,
    onTranscribe: () -> Unit,
    onRetranscribe: (List<String>) -> Unit,
) {
    val untranscribed = session.recordings.count {
        it.durationMs > 0 && it.transcript == null && !transcription.isPending(session.id, it.id)
    }
    val busy = transcription.isBusyWith(session.id)
    Column(Modifier.fillMaxWidth()) {
        if (recordingHere && live.active && live.sessionId == session.id) LiveBanner(live.speaking)
        desktopProgress?.let { DesktopStatus(session.id, it) }
        if (busy) {
            TranscribeProgress(session, transcription)
        } else if (untranscribed > 0) {
            TranscribePrompt(untranscribed, transcription.modelReady, onTranscribe)
        }
        transcription.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { TranscriptionController.clearError() }) { Text("Dismiss") }
            }
        }
        // Transcripts from before the current phone model (or of unknown origin) can be redone.
        val outdated = session.recordings.filter {
            it.durationMs > 0 && it.transcript != null && it.transcriptModel == null && !transcription.isPending(session.id, it.id)
        }
        if (outdated.isNotEmpty() && !recordingHere) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Made with the older, less accurate speech model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRetranscribe(outdated.map { it.id }) }) { Text("Transcribe again") }
            }
        }
    }
}

/** Which models the session's transcript came from, for the end of its timeline. */
@Composable
fun TranscribedWith(session: Session) {
    val models = session.recordings.filter { !it.transcript.isNullOrEmpty() }.mapNotNull { it.transcriptModel }.distinct()
    if (models.isEmpty()) return
    Text(
        "Transcribed with " + models.joinToString { modelLabel(it) },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** A readable name for the model that made a transcript. */
fun modelLabel(id: String): String = if (id == SpeechModel.ID) "${SpeechModel.NAME} on this phone" else "$id on your desktop"

@Composable
private fun DesktopStatus(sessionId: String, progress: DesktopSync.Progress) {
    val percent = (progress.fraction * 100).toInt()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (progress.phase) {
                    DesktopSync.Phase.SENDING -> "Sending to your desktop… $percent%"
                    DesktopSync.Phase.TRANSCRIBING -> "Transcribing on your desktop… $percent%"
                    DesktopSync.Phase.DONE -> "The transcript from your desktop is in."
                    DesktopSync.Phase.FAILED -> progress.message ?: "Sending to the desktop failed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.phase == DesktopSync.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when (progress.phase) {
                DesktopSync.Phase.FAILED -> TextButton(onClick = { DesktopSync.send(sessionId) }) { Text("Retry") }
                DesktopSync.Phase.DONE -> TextButton(onClick = { DesktopSync.clear(sessionId) }) { Text("OK") }
                else -> Unit
            }
        }
        if (progress.phase == DesktopSync.Phase.SENDING || progress.phase == DesktopSync.Phase.TRANSCRIBING) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LiveBanner(speaking: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (speaking) RecordRed else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (speaking) "Listening…" else "Transcribing as you record",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranscribePrompt(count: Int, modelReady: Boolean, onTranscribe: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (count == 1) "Not transcribed yet" else "$count recordings not transcribed yet",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Transcribe to read and search what was said. It runs on your phone; nothing is uploaded." +
                    if (modelReady) "" else " The first time, it downloads the speech model (${SpeechModel.totalBytes / 1_000_000} MB).",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onTranscribe) { Text("Transcribe") }
        }
    }
}

@Composable
private fun TranscribeProgress(session: Session, state: TranscriptionController.State) {
    val mine = state.current?.sessionId == session.id
    val percent = (state.progress * 100).toInt()
    val label = when {
        !mine -> "Waiting to transcribe…"
        state.phase == TranscriptionController.Phase.DOWNLOADING_MODEL -> "Downloading the speech model… $percent%"
        session.recordings.size > 1 -> "Transcribing recording ${session.recordingNumber(state.current?.recId)}… $percent%"
        else -> "Transcribing… $percent%"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { TranscriptionController.cancel(session.id) }) { Text("Stop") }
        }
        if (mine) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** [text] with [matches] highlighted. */
@Composable
fun highlighted(text: String, matches: List<IntRange>): AnnotatedString {
    val background = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(text, matches, background) {
        buildAnnotatedString {
            append(text)
            for (r in matches) {
                if (r.first >= 0 && r.last < text.length) {
                    addStyle(SpanStyle(background = background, color = foreground, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
                }
            }
        }
    }
}

/** Confirms the one-time speech model download before the first transcription. */
@Composable
fun ModelDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, confirmLabel: String = "Download and transcribe") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download the speech model?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Transcription needs a ${SpeechModel.totalBytes / 1_000_000} MB speech model (${SpeechModel.NAME}), " +
                        "downloaded once; use Wi-Fi. After that it runs entirely on your phone and nothing is uploaded.\n\n" +
                        "From then on, recordings are transcribed live while you record; you can turn that off in the main menu.",
                )
                val uriHandler = LocalUriHandler.current
                Text("${SpeechModel.LICENSE_NOTICE}.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uriHandler.openUri(SpeechModel.LICENSE_URL) }, contentPadding = PaddingValues(0.dp)) {
                    Text("View the licence")
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
