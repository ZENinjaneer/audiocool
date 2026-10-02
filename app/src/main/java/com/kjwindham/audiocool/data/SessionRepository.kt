package com.kjwindham.audiocool.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.audio.remuxAdtsToM4a
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * All sessions, held in memory and mirrored to files/sessions/<id>/session.json.
 * A session's audio files sit in the same folder as its JSON.
 */
object SessionRepository {
    private const val TAG = "SessionRepository"

    private lateinit var root: File
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    // A single thread, so writes land on disk in the order they were made.
    private val io = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    // Slow audio-file work (duration repair, .aac -> .m4a), one file at a time, off the write thread.
    private val background = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    fun init(context: Context) {
        root = File(context.filesDir, "sessions").apply { mkdirs() }
        val loaded = root.listFiles().orEmpty().mapNotNull(::load)
        _sessions.value = loaded
        background.launch {
            repairDurations(loaded)
            // Recordings still in .aac were interrupted before conversion (e.g. the app was killed).
            for (session in loaded) for (rec in session.recordings) convertNow(session.id, rec.id)
        }
    }

    fun sessionDir(id: String): File = File(root, id)

    fun audioFile(sessionId: String, rec: Recording): File = File(sessionDir(sessionId), rec.file)

    fun photoFile(sessionId: String, name: String): File = File(sessionDir(sessionId), name)

    fun get(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    fun create(title: String): Session {
        val now = System.currentTimeMillis()
        val s = Session(id = newId(), title = title, createdAt = now, updatedAt = now)
        _sessions.update { it + s }
        persist(s.id)
        return s
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title) }

    fun delete(id: String) {
        _sessions.update { list -> list.filterNot { it.id == id } }
        io.launch { sessionDir(id).deleteRecursively() }
    }

    fun addRecording(sessionId: String, rec: Recording) =
        update(sessionId) { it.copy(recordings = it.recordings + rec) }

    fun setRecordingDuration(sessionId: String, recId: String, durationMs: Long) =
        update(sessionId, touch = false) { s ->
            s.copy(recordings = s.recordings.map { if (it.id == recId) it.copy(durationMs = durationMs) else it })
        }

    /** Converts a finished .aac recording to .m4a in the background; see [remuxAdtsToM4a]. */
    fun convertToM4a(sessionId: String, recId: String) {
        background.launch { convertNow(sessionId, recId) }
    }

    fun setTranscript(sessionId: String, recId: String, transcript: List<TranscriptSegment>, model: String? = null) =
        update(sessionId, touch = false) { s ->
            s.copy(recordings = s.recordings.map { if (it.id == recId) it.copy(transcript = transcript, transcriptModel = model) else it })
        }

    /** Adds one phrase to a recording's transcript (live transcription). */
    fun appendTranscriptSegment(sessionId: String, recId: String, segment: TranscriptSegment, model: String? = null) =
        update(sessionId, touch = false) { s ->
            s.copy(
                recordings = s.recordings.map {
                    if (it.id == recId) it.copy(transcript = it.transcript.orEmpty() + segment, transcriptModel = model) else it
                },
            )
        }

    /** Adds a session restored from a backup; its audio files must already be in [sessionDir]. */
    fun importSession(session: Session): Boolean {
        var added = false
        _sessions.update { list ->
            added = list.none { it.id == session.id }
            if (added) list + session else list
        }
        if (added) persist(session.id)
        return added
    }

    fun addNote(sessionId: String, note: Note) = update(sessionId) { it.copy(notes = it.notes + note) }

    fun editNote(sessionId: String, noteId: String, text: String) = update(sessionId) { s ->
        s.copy(notes = s.notes.map { if (it.id == noteId) it.copy(text = text) else it })
    }

    fun deleteNote(sessionId: String, noteId: String) {
        val photo = get(sessionId)?.notes?.firstOrNull { it.id == noteId }?.photo
        update(sessionId) { s -> s.copy(notes = s.notes.filterNot { it.id == noteId }, thumbnail = s.thumbnail.takeIf { it != noteId }) }
        if (photo != null) io.launch { photoFile(sessionId, photo).delete() }
    }

    /** Saves the text found in a photo note's picture. */
    fun setPhotoText(sessionId: String, noteId: String, text: String) = update(sessionId, touch = false) { s ->
        s.copy(notes = s.notes.map { if (it.id == noteId) it.copy(photoText = text) else it })
    }

    /** Saves a chapter's summary in place of any earlier one, and drops those of chapters not in [chapters] any more. */
    fun setChapterSummary(sessionId: String, summary: ChapterSummary, chapters: Set<String>) = update(sessionId, touch = false) { s ->
        s.copy(chapterSummaries = s.chapterSummaries.filter { it.key != summary.key && it.key in chapters } + summary)
    }

    fun setSessionSummary(sessionId: String, summary: SessionSummary?) = update(sessionId, touch = false) { it.copy(summary = summary) }

    /** Shows the photo note [noteId] for the session in lists. */
    fun setThumbnail(sessionId: String, noteId: String) = update(sessionId) { it.copy(thumbnail = noteId) }

    /** Waits for queued disk writes and deletes to finish; for tests. */
    @VisibleForTesting
    fun awaitIo() = runBlocking { io.launch { }.join() }

    fun readDurationMs(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun update(id: String, touch: Boolean = true, transform: (Session) -> Session) {
        val now = System.currentTimeMillis()
        _sessions.update { list ->
            list.map { s ->
                if (s.id != id) s else transform(s).let { if (touch) it.copy(updatedAt = now) else it }
            }
        }
        persist(id)
    }

    private fun persist(id: String) {
        io.launch {
            // Write whatever is newest when this runs; a deleted session is skipped.
            val s = get(id) ?: return@launch
            val file = AtomicFile(File(sessionDir(id).apply { mkdirs() }, "session.json"))
            val out = file.startWrite()
            try {
                out.write(SessionJson.encode(s).toByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                Log.e(TAG, "Couldn't save session $id", e)
            }
        }
    }

    private fun load(dir: File): Session? = try {
        SessionJson.decode(String(AtomicFile(File(dir, "session.json")).readFully()))
    } catch (e: FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read session ${dir.name}", e)
        null
    }

    private fun convertNow(sessionId: String, recId: String) {
        val rec = get(sessionId)?.recording(recId) ?: return
        if (!rec.file.endsWith(".aac")) return
        val src = audioFile(sessionId, rec)
        if (src.length() == 0L) return
        val dst = File(src.parentFile, rec.file.removeSuffix(".aac") + ".m4a")
        if (!remuxAdtsToM4a(src, dst)) return
        // Only swap files once the copy demonstrably holds the whole recording.
        val srcMs = rec.durationMs.takeIf { it > 0 } ?: readDurationMs(src)
        val dstMs = readDurationMs(dst)
        if (dstMs <= 0 || (srcMs > 0 && abs(dstMs - srcMs) > 2_000)) {
            Log.w(TAG, "Converted ${rec.file} is ${dstMs} ms, expected ${srcMs} ms; keeping the original")
            dst.delete()
            return
        }
        var replaced = false
        update(sessionId, touch = false) { s ->
            s.copy(
                recordings = s.recordings.map {
                    if (it.id == recId) {
                        replaced = true
                        it.copy(file = dst.name)
                    } else {
                        it
                    }
                },
            )
        }
        // A player that has the old file open keeps working; the next load uses the .m4a.
        if (replaced) src.delete() else dst.delete()
    }

    /** A recording left at 0 ms was cut off (e.g. the app was killed); read its real length from the file. */
    private fun repairDurations(sessions: List<Session>) {
        for (s in sessions) for (r in s.recordings) {
            if (r.durationMs > 0) continue
            val f = audioFile(s.id, r)
            if (f.length() == 0L) continue
            val ms = readDurationMs(f)
            if (ms > 0) setRecordingDuration(s.id, r.id, ms)
        }
    }
}
