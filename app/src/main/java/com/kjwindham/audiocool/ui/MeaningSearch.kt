package com.kjwindham.audiocool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.search.MeaningIndex
import com.kjwindham.audiocool.search.MeaningModel
import com.kjwindham.audiocool.search.SearchHit
import com.kjwindham.audiocool.summarize.SummaryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * What in [sessions] is close in meaning to [query] (once typing has paused), less what the word-for-word
 * [found] already has; nothing while search by meaning isn't set up.
 */
@Composable
fun rememberMeaningHits(sessions: List<Session>, query: String, found: List<SearchHit>): List<SearchHit> {
    val meaning by SummaryController.meaning.collectAsStateWithLifecycle()
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val ids = sessions.map { it.id }
    LaunchedEffect(query, meaning.ready, meaning.indexed, ids) {
        hits = emptyList()
        val q = query.trim()
        if (!meaning.ready || q.length < 3) return@LaunchedEffect
        delay(500)
        val vector = suspendCancellableCoroutine<FloatArray?> { cont -> SummaryController.meaningOf(q) { v -> if (cont.isActive) cont.resume(v) } } ?: return@LaunchedEffect
        hits = withContext(Dispatchers.Default) {
            MeaningIndex.search(sessions, vector).map { f ->
                val p = f.passage
                SearchHit(f.sessionId, p.kind, p.text.take(400), emptyList(), p.recId, p.atMs, p.noteId, chapterKey = p.chapterKey)
            }
        }
    }
    // Not what the words found already (a paragraph holding a phrase found, or the same note).
    return remember(hits, found) {
        hits.filterNot { h ->
            found.any { f ->
                f.sessionId == h.sessionId && f.kind == h.kind && (
                    f.noteId != null && f.noteId == h.noteId ||
                        f.chapterKey != null && f.chapterKey == h.chapterKey ||
                        f.recId == h.recId && f.atMs != null && h.atMs != null && f.atMs in h.atMs..(h.atMs + 60_000)
                    )
            }
        }
    }
}

/** Setting up search by meaning: what it is, its download, and deleting it. */
@Composable
fun MeaningDialog(onDismiss: () -> Unit) {
    val state by SummaryController.meaning.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search by meaning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Find things by what they mean, not just their words: \"why we get sleepy\" finds \"adenosine builds up " +
                        "while you're awake\". Results show under By meaning in a search.",
                )
                when {
                    state.downloading -> {
                        Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    state.ready -> Text(
                        if (state.indexed == 0) "On. Your sessions are being read for it, in the background." else "On. ${state.indexed} session${if (state.indexed == 1) "" else "s"} ready to search by meaning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        "It needs a model (${MeaningModel.SIZE / 1_000_000} MB, IBM's Granite Embedding), downloaded once; use Wi-Fi. " +
                            "It runs on your phone, like the summaries, and nothing is uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when {
                state.downloading -> TextButton(onClick = { SummaryController.cancelMeaningDownload() }) { Text("Stop") }
                state.ready -> TextButton(onClick = onDismiss) { Text("OK") }
                else -> TextButton(onClick = { SummaryController.downloadMeaning() }) { Text("Download") }
            }
        },
        dismissButton = {
            if (state.ready && !state.downloading) {
                TextButton(onClick = {
                    SummaryController.deleteMeaning()
                    onDismiss()
                }) { Text("Turn off and delete") }
            } else if (!state.downloading) {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
    )
}

/** A heading over the results found by meaning. */
@Composable
fun ByMeaningHeading() {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp)) {
        Text("✦ By meaning", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Close in meaning, if not in words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
