package com.kjwindham.audiocool.util

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "05:07" under an hour, "1:02:05" from an hour on. */
fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatDate(ms: Long): String = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(ms))

fun defaultSessionTitle(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(now))

/** "03:12", or "#2 03:12" when the session has several recordings. Null if the note isn't linked. */
fun noteLabel(session: Session, note: Note): String? {
    val offset = note.offsetMs ?: return null
    val number = session.recordingNumber(note.recId)
    if (number == 0) return null
    val time = formatTime(offset)
    return if (session.recordings.size > 1) "#$number $time" else time
}

/**
 * True when the edit from [old] to [new] is the Enter key: one new line break, either typed on its
 * own or at the end of the text (keyboards often commit an autocorrection and the Enter together).
 * A pasted block with a line break in the middle doesn't count.
 */
fun isEnterKeystroke(old: String, new: String): Boolean {
    val added = new.count { it == '\n' } - old.count { it == '\n' }
    return added == 1 && (new.endsWith('\n') || new.length == old.length + 1)
}

/** [new] without the line break that [isEnterKeystroke] detected. */
fun removeEnter(old: String, new: String): String =
    if (new.endsWith('\n')) {
        new.dropLast(1)
    } else {
        val at = old.commonPrefixWith(new).length
        new.removeRange(at, at + 1)
    }

fun sessionMarkdown(session: Session): String = buildString {
    appendLine("# ${session.title}")
    append(formatDate(session.createdAt))
    val recs = session.recordings
    if (recs.isNotEmpty()) {
        append(" · ${recs.size} recording${if (recs.size > 1) "s" else ""} · ${formatTime(session.totalDurationMs)}")
    }
    appendLine()
    appendLine()
    for (n in session.orderedNotes()) {
        val label = noteLabel(session, n)
        appendLine(if (label != null) "- [$label] ${n.text}" else "- ${n.text}")
    }
    if (recs.isNotEmpty()) {
        appendLine()
        appendLine("Audio: " + recs.joinToString { "${it.file} (${formatTime(it.durationMs)})" })
    }
}
