package com.kjwindham.audiocool.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import java.io.File

/** Opens the share sheet with the notes (as text, and as a .md file with the transcript) plus the audio and photos. */
fun shareSession(context: Context, session: Session) {
    val markdown = sessionMarkdown(session)
    val dir = File(context.cacheDir, "share").apply {
        deleteRecursively()
        mkdirs()
    }
    val name = session.title.replace(Regex("[^\\w .-]"), "_").trim().take(60).ifEmpty { "notes" }
    // The attached file also carries the transcript; the message text stays short.
    val notesFile = File(dir, "$name.md").apply { writeText(sessionMarkdown(session, includeTranscript = true)) }

    val authority = "${context.packageName}.files"
    val uris = ArrayList<Uri>()
    uris += FileProvider.getUriForFile(context, authority, notesFile)
    session.recordings
        .map { SessionRepository.audioFile(session.id, it) }
        .filter { it.length() > 0 }
        .forEach { uris += FileProvider.getUriForFile(context, authority, it) }
    session.orderedNotes()
        .mapNotNull { n -> n.photo?.let { SessionRepository.photoFile(session.id, it) } }
        .filter { it.isFile }
        .forEach { uris += FileProvider.getUriForFile(context, authority, it) }

    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        putExtra(Intent.EXTRA_TEXT, markdown)
        clipData = ClipData.newRawUri(session.title, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share notes and audio"))
}

/** Shares just the session's recordings. */
fun shareAudio(context: Context, session: Session) {
    val authority = "${context.packageName}.files"
    val uris = ArrayList(
        session.recordings.map { SessionRepository.audioFile(session.id, it) }.filter { it.length() > 0 }.map { FileProvider.getUriForFile(context, authority, it) },
    )
    if (uris.isEmpty()) return
    val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "audio/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        clipData = ClipData.newRawUri(session.title, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share the audio"))
}

/** Shares the session's summary, key points and action items, as text. */
fun shareSummary(context: Context, session: Session) {
    val summary = session.summary ?: return
    val text = buildString {
        appendLine(session.title)
        appendLine()
        appendLine(summary.text)
        if (summary.keyPoints.isNotEmpty()) {
            appendLine()
            appendLine("Key points:")
            summary.keyPoints.forEach { appendLine("- $it") }
        }
        if (summary.actionItems.isNotEmpty()) {
            appendLine()
            appendLine("Action items:")
            summary.actionItems.forEach { appendLine("- $it") }
        }
    }.trim()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share the summary"))
}

/** Shares a session's web page (see share/WebPage). */
fun sharePage(context: Context, page: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", page)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share the web page"))
}
