package com.kjwindham.audiocool.util

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.speakers.Voices
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

/** "03:12", or "#2 03:12" when the session has several recordings. Null if the recording is gone. */
fun timeLabel(session: Session, recId: String?, offsetMs: Long): String? {
    val number = session.recordingNumber(recId)
    if (number == 0) return null
    val time = formatTime(offsetMs)
    return if (session.recordings.size > 1) "#$number $time" else time
}

/** The note's [timeLabel], or null if the note isn't linked to the audio. */
fun noteLabel(session: Session, note: Note): String? = note.offsetMs?.let { timeLabel(session, note.recId, it) }

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

fun sessionMarkdown(session: Session, includeTranscript: Boolean = false): String = buildString {
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
        // A photo shows as an image (its file travels with the notes when shared or backed up).
        val text = n.photo?.let { "![${n.text.ifBlank { "Photo" }}]($it)" } ?: n.text
        appendLine(if (label != null) "- [$label] $text" else "- $text")
        // Then what the photo says, quoted under it.
        n.textInPhoto?.lines()?.forEach { appendLine("  > $it".trimEnd()) }
    }
    session.summary?.takeIf { it.text.isNotBlank() }?.let { s ->
        appendLine()
        appendLine("## Summary")
        appendLine()
        appendLine(s.text)
        if (s.keyPoints.isNotEmpty()) {
            appendLine()
            appendLine("Key points:")
            s.keyPoints.forEach { appendLine("- $it") }
        }
        if (s.actionItems.isNotEmpty()) {
            appendLine()
            appendLine("Action items:")
            s.actionItems.forEach { appendLine("- [ ] $it") }
        }
    }
    val parts = com.kjwindham.audiocool.summarize.chapters(session) { null }.filterNot { it.slight }
        .mapNotNull { c -> session.chapterSummaries.firstOrNull { it.key == c.key }?.text?.takeIf { it.isNotBlank() }?.let { c to it } }
    if (parts.size > 1) {
        appendLine()
        appendLine("## Part by part")
        appendLine()
        for ((c, text) in parts) {
            val slide = c.photo?.let { p -> p.text.ifBlank { p.textInPhoto?.lineSequence()?.firstOrNull() ?: "" } }?.takeIf { it.isNotBlank() }
            appendLine("- [${timeLabel(session, c.recId, c.startMs)}]${slide?.let { " $it:" } ?: ""} $text")
        }
    }
    if (recs.isNotEmpty()) {
        appendLine()
        appendLine("Audio: " + recs.joinToString { "${it.file} (${formatTime(it.durationMs)})" })
    }
    if (includeTranscript && recs.any { !it.transcript.isNullOrEmpty() }) {
        appendLine()
        appendLine("## Transcript")
        appendLine()
        for (rec in recs) {
            // Who's speaking, where it changes (when the session was sorted by voice).
            var lastVoice: Int? = null
            for (segment in rec.transcript.orEmpty().flatMap { Voices.splitBySpeaker(it, rec.speakers) }) {
                val voice = Voices.voiceAt(rec.speakers, segment.startMs, segment.endMs)
                val who = if (voice != null && voice != lastVoice) "**${Voices.name(session, voice)}:** " else ""
                if (voice != null) lastVoice = voice
                appendLine("- [${timeLabel(session, rec.id, segment.startMs)}] $who${segment.text}")
            }
        }
    }
}
