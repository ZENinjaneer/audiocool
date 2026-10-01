package com.kjwindham.audiocool.backup

import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.util.sessionMarkdown
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup format: one folder per session, named by start time and id, holding session.json (all the
 * data, for restoring), the audio files and photos, and notes.md (notes and transcript, readable
 * without the app).
 */
object BackupManager {
    private val AUDIO = Regex("""recording-\d+\.(aac|m4a)""")
    private val PHOTO = Regex("""photo-[\w-]+\.jpg""")

    fun folderName(session: Session): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(session.createdAt)) + "_" + session.id

    /** Brings [session]'s folder in [root] up to date from its files in [sessionDir], copying only new or changed ones. */
    fun backUp(root: BackupFolder, session: Session, sessionDir: File) {
        val dir = root.folder(folderName(session)) ?: root.createFolder(folderName(session))
        // A recording still in progress (no duration yet) is copied once it's finished.
        val files = session.recordings.filter { it.durationMs > 0 }.map { it.file } + session.photoFiles()
        for (name in files) {
            val local = File(sessionDir, name)
            if (local.isFile && dir.fileSize(name) != local.length()) local.inputStream().use { dir.write(name, it) }
        }
        // Remove files the session no longer uses, e.g. an .aac that was converted to .m4a, or a deleted photo.
        val used = (session.recordings.map { it.file } + session.photoFiles()).toSet()
        dir.fileNames().filter { (AUDIO.matches(it) || PHOTO.matches(it)) && it !in used }.forEach { dir.delete(it) }
        dir.write("session.json", SessionJson.encode(session).byteInputStream())
        dir.write("notes.md", sessionMarkdown(session, includeTranscript = true).byteInputStream())
    }

    /**
     * Restores the sessions in [root] that the app doesn't have ([exists]), copying their audio and
     * photos into [sessionDir] before handing each to [import]. Returns how many were restored.
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
            for (name in session.recordings.map { it.file } + session.photoFiles()) {
                dir.read(name)?.use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
            }
            import(session)
            restored++
        }
        return restored
    }
}
