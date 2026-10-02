package com.kjwindham.audiocool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.summarize.SummaryModel
import com.kjwindham.audiocool.util.Prefs

/**
 * The session's summary at the top of its timeline: the summary, key points and action items once
 * they're made; until then, that they're on the way, or an offer to set summaries up.
 */
@Composable
fun SummaryCard(session: Session) {
    val context = LocalContext.current
    val state by SummaryController.state.collectAsStateWithLifecycle()
    val prefs = remember { Prefs(context) }
    var offerDismissed by remember { mutableStateOf(prefs.summaryOfferDismissed) }
    var confirmDownload by remember { mutableStateOf(false) }
    val summary = session.summary?.takeIf { it.text.isNotBlank() }
    val pending = remember(session, state.modelReady, state.sessionId) { SummaryController.pending(session) }
    val hasSpeech = session.recordings.any { !it.transcript.isNullOrEmpty() }
    when {
        summary != null -> SummaryShown(summary, updating = pending && state.sessionId == session.id)
        state.downloading -> Card(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading the summary model… ${(state.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { SummaryController.cancelDownload() }) { Text("Stop") }
                }
                LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
            }
        }
        pending -> Text(
            if (state.sessionId == session.id && state.total > 0) {
                "✦ Summarizing on your phone… part ${minOf(state.done + 1, state.total)} of ${state.total}"
            } else {
                "✦ A summary is on its way."
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
        )
        !state.modelReady && hasSpeech && !offerDismissed -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("✦ Summaries", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A few sentences on each slide, and a summary of the whole session with its key points and action items. " +
                        "Made on your phone, as you record.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        prefs.summaryOfferDismissed = true
                        offerDismissed = true
                    }) { Text("Not now") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { confirmDownload = true }) { Text("Set up") }
                }
            }
        }
        else -> Unit
    }
    state.error?.let { error ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { SummaryController.clearError() }) { Text("Dismiss") }
        }
    }
    if (confirmDownload) {
        SummaryDownloadDialog(
            onConfirm = {
                confirmDownload = false
                SummaryController.download()
            },
            onDismiss = { confirmDownload = false },
        )
    }
}

@Composable
private fun SummaryShown(summary: SessionSummary, updating: Boolean) {
    var expanded by rememberSaveable(summary.basis) { mutableStateOf(false) }
    val more = summary.keyPoints.isNotEmpty() || summary.actionItems.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (more) 4.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✦ Summary", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        updating -> "Updating…"
                        summary.model.endsWith("@desktop") -> "Made on your desktop"
                        else -> "Made on this phone"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                summary.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (expanded) {
                if (summary.keyPoints.isNotEmpty()) {
                    Text("Key points", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    summary.keyPoints.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp)) }
                }
                if (summary.actionItems.isNotEmpty()) {
                    Text("Action items", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    summary.actionItems.forEach { Text("☐  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp)) }
                }
            }
            if (more) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(
                        when {
                            expanded -> "Show less"
                            summary.actionItems.isNotEmpty() -> "Key points and ${summary.actionItems.size} action item${if (summary.actionItems.size == 1) "" else "s"}"
                            else -> "Key points"
                        },
                    )
                }
            }
        }
    }
}

/** Confirms the one-time download of the summary model. */
@Composable
fun SummaryDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download the summary model?") },
        text = { SummaryModelAbout() },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SummaryModelAbout() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Summaries are made on your phone by ${SummaryModel.NAME}, a ${"%.1f".format(SummaryModel.SIZE / 1e9)} GB download " +
                "(once; use Wi-Fi). Nothing is uploaded. While it works it also keeps a cache of about 0.8 GB, which Android can clear.",
        )
        Text("${SummaryModel.LICENSE_NOTICE}.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uriHandler.openUri(SummaryModel.LICENSE_URL) }, contentPadding = PaddingValues(0.dp)) {
            Text("About the model")
        }
    }
}

/** Summaries, from the main menu: download the model, turn them on or off, see where they run, free the space. */
@Composable
fun SummariesDialog(onDismiss: () -> Unit) {
    val state by SummaryController.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(SummaryController.enabled) }
    var gpu by remember { mutableStateOf(SummaryController.useGpu) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Summaries") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.downloading -> {
                        Text("Downloading ${SummaryModel.NAME}… ${(state.downloadProgress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { SummaryController.cancelDownload() }) { Text("Stop the download") }
                    }
                    !state.modelReady -> {
                        Text("A few sentences on each slide, and a summary of each session with its key points and action items, made as you record.")
                        SummaryModelAbout()
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Summarize recordings", modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = {
                                on = it
                                SummaryController.setEnabled(it)
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Use the GPU")
                                Text(
                                    if (SummaryController.gpuFailed) "It didn't work on this phone's GPU, so it uses the CPU." else "Faster where it works; experimental.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = gpu, onCheckedChange = {
                                gpu = it
                                SummaryController.useGpu = it
                            })
                        }
                        Text(
                            buildString {
                                append("${SummaryModel.NAME} runs on your phone, in the background")
                                state.where?.let { append(" (last on its ${it.replace("CPU (", "CPU, ").removeSuffix(")")})") }
                                append(".")
                                state.speed?.let { (_, write) -> append(" It last wrote about ${(write * 0.75).toInt()} words a second.") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete the model (frees ${"%.1f".format(SummaryModel.SIZE / 1e9)} GB)") }
                    }
                }
            }
        },
        confirmButton = {
            if (!state.modelReady && !state.downloading) {
                TextButton(onClick = { SummaryController.download() }) { Text("Download") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = if (!state.modelReady && !state.downloading) {
            { TextButton(onClick = onDismiss) { Text("Not now") } }
        } else {
            null
        },
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete the summary model?",
            message = "Summaries already made stay. New ones need the model downloaded again.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                SummaryController.deleteModel()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
