package com.kjwindham.audiocool.backup

import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.util.sessionMarkdown
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup format: one folder per session, named by start time and id, holding session.json (all the
 * data, for restoring), the audio files, and notes.md (notes and transcript, readable without the app).
 */
object BackupManager {
    private val AUDIO = Regex("""recording-\d+\.(aac|m4a)""")

    fun folderName(session: Session): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(session.createdAt)) + "_" + session.id

    /** Brings [session]'s folder in [root] up to date, copying only audio that's new or changed. */
    fun backUp(root: BackupFolder, session: Session, audioFile: (Recording) -> File) {
        val dir = root.folder(folderName(session)) ?: root.createFolder(folderName(session))
        // A recording still in progress (no duration yet) is copied once it's finished.
        for (rec in session.recordings.filter { it.durationMs > 0 }) {
            val local = audioFile(rec)
            if (local.isFile && dir.fileSize(rec.file) != local.length()) local.inputStream().use { dir.write(rec.file, it) }
        }
        // Remove audio the session no longer uses, e.g. an .aac that was converted to .m4a.
        val used = session.recordings.map { it.file }.toSet()
        dir.fileNames().filter { AUDIO.matches(it) && it !in used }.forEach { dir.delete(it) }
        dir.write("session.json", SessionJson.encode(session).byteInputStream())
        dir.write("notes.md", sessionMarkdown(session, includeTranscript = true).byteInputStream())
    }

    /**
     * Restores the sessions in [root] that the app doesn't have ([exists]), copying their audio into
     * [sessionDir] before handing each to [import]. Returns how many were restored.
     */
    fun restore(
        root: BackupFolder,
        exists: (String) -> Boolean,
        sessionDir: (String) -> File,
        import: (Session) -> Unit,
    ): Int {
        var restored = 0
        for (dir in root.folders()) {
            val json = dir.read("session.json")?.use { it.readBytes().decodeToString() } ?: continue
            val session = runCatching { SessionJson.decode(json) }.getOrNull() ?: continue
            if (exists(session.id)) continue
            val target = sessionDir(session.id).apply { mkdirs() }
            for (rec in session.recordings) {
                dir.read(rec.file)?.use { input -> File(target, rec.file).outputStream().use { input.copyTo(it) } }
            }
            import(session)
            restored++
        }
        return restored
    }
}
