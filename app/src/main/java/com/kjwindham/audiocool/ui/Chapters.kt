package com.kjwindham.audiocool.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.summarize.parseChapterKey
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.timeLabel

/**
 * The session's chapters at a glance: each one's title, what it's about and where it starts, the one
 * playing marked. Picking one goes there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    session: Session,
    chapters: List<TimelineRow.Summary>,
    playing: TimelineRow.Summary?,
    onPick: (TimelineRow.Summary) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Chapters", style = MaterialTheme.typography.titleLarge)
            Text(
                "${chapters.size} chapters · ${formatTime(session.totalDurationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn {
                items(chapters, key = { it.key }) { c ->
                    val at = parseChapterKey(c.chapterKey)?.let { (recId, ms) -> timeLabel(session, recId, ms) }.orEmpty()
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                    ) {
                        Text(
                            "${c.number}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.widthIn(min = 28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.title ?: "Chapter ${c.number}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (c == playing) "Playing" else firstSentence(c.text),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c == playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(at, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun firstSentence(text: String): String {
    val end = Regex("[.!?](\\s|$)").find(text)?.range?.first
    return (if (end != null) text.substring(0, end + 1) else text).trim()
}
