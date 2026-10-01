package com.kjwindham.audiocool.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import java.io.File

/** Opens the share sheet with the notes (as text, and as a .md file with the transcript) plus the audio files. */
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
