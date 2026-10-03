package com.kjwindham.audiocool.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.share.WebPage

/** What to share of a session: a web page of all of it, the notes and audio, just the audio, or the summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(session: Session, onPage: () -> Unit, onNotes: () -> Unit, onAudio: () -> Unit, onSummary: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Share", style = MaterialTheme.typography.titleLarge)
            Text(session.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            val hasAudio = session.recordings.any { it.durationMs > 0 }
            val megabytes = (WebPage.estimate(session) / 1_000_000).coerceAtLeast(1)
            Option(
                "Web page",
                "One file with " + (if (hasAudio) "the audio, " else "") + "slides, notes and transcript. Opens in any browser; nothing to install. About $megabytes MB.",
                badge = "New",
                onClick = onPage,
            )
            Option("Notes and audio", "The notes as text with the summary and transcript, plus the recording and photos.", onClick = onNotes)
            if (hasAudio) Option("Just the audio", "The recording on its own.", onClick = onAudio)
            if (session.summary != null) Option("✦ Summary", "The summary, key points and action items as text.", onClick = onSummary)
        }
    }
}

@Composable
private fun Option(title: String, detail: String, badge: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
