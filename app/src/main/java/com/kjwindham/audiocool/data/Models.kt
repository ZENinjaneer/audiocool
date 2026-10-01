package com.kjwindham.audiocool.data

import java.util.UUID

/** One continuous audio file. Notes point into it with [Note.recId] + [Note.offsetMs]. */
data class Recording(
    val id: String,
    /** File name inside the session's folder. */
    val file: String,
    /** Wall-clock time the recording started. */
    val createdAt: Long,
    /** 0 while recording, or if the app was killed before the length was saved. */
    val durationMs: Long,
    /** What was said, in order; null until the recording has been transcribed. */
    val transcript: List<TranscriptSegment>? = null,
    /** Which speech model produced [transcript] (phone or desktop), if known. */
    val transcriptModel: String? = null,
)

/** A stretch of speech in a recording and the text recognized in it. */
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class Note(
    val id: String,
    val text: String,
    val createdAt: Long,
    val recId: String? = null,
    /** Position in the recording, excluding paused time, so it matches the audio file. */
    val offsetMs: Long? = null,
    /** A photo (of a slide, say): a JPEG in the session's folder. [text] is then its caption. */
    val photo: String? = null,
    /** The text found in [photo], one line per line; null until it's been read. */
    val photoText: String? = null,
)

data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val recordings: List<Recording> = emptyList(),
    val notes: List<Note> = emptyList(),
    /** The photo note picked to stand for the session in lists; by default, the first photo. */
    val thumbnail: String? = null,
) {
    val totalDurationMs: Long get() = recordings.sumOf { it.durationMs }

    /** The photo files in this session's folder. */
    fun photoFiles(): List<String> = notes.mapNotNull { it.photo }

    /** The photo shown for this session in lists, if it has any. */
    fun thumbnailNote(): Note? =
        notes.firstOrNull { it.id == thumbnail && it.photo != null } ?: orderedNotes().firstOrNull { it.photo != null }

    fun recording(id: String?): Recording? = recordings.firstOrNull { it.id == id }

    /** 1-based position of a recording in this session, or 0 if it isn't here. */
    fun recordingNumber(id: String?): Int = recordings.indexOfFirst { it.id == id } + 1

    /**
     * Notes in timeline order. A linked note sorts by the moment of audio it points at, so notes
     * added while replaying land next to what they're about; unlinked notes sort by when they
     * were written.
     */
    fun orderedNotes(): List<Note> = notes.sortedWith(compareBy<Note>({ timelineKey(it) }, { it.createdAt }))

    private fun timelineKey(note: Note): Long {
        val rec = recording(note.recId)
        val offset = note.offsetMs
        return if (rec != null && offset != null) rec.createdAt + offset else note.createdAt
    }
}

/**
 * The linked note in [recId] whose timestamp is the latest one at or before [positionMs]. Among
 * notes with the same timestamp, the one later in [notes] wins.
 */
fun currentNoteId(notes: List<Note>, recId: String?, positionMs: Long): String? =
    notes.withIndex()
        .filter { (_, n) -> n.recId == recId && n.offsetMs != null && n.offsetMs <= positionMs }
        .maxWithOrNull(compareBy<IndexedValue<Note>>({ it.value.offsetMs }, { it.index }))
        ?.value?.id

/** A note the user tapped to play: it stays highlighted from where playback started until playback reaches it. */
data class NoteFocus(val noteId: String, val recId: String, val fromMs: Long, val untilMs: Long)

/**
 * Where playback of [note] starts: [leadInMs] before it, so you hear what led to it, but never
 * before the previous note in the same recording, so you land in this note's part of the audio.
 */
fun playbackStartFor(note: Note, notes: List<Note>, leadInMs: Long): Long {
    val offset = note.offsetMs ?: return 0L
    val previous = notes.filter { it.recId == note.recId }.mapNotNull { it.offsetMs }.filter { it < offset }.maxOrNull() ?: 0L
    return maxOf(offset - leadInMs, previous, 0L)
}

/**
 * The note to highlight at [positionMs]: a just-tapped note until playback reaches it (otherwise
 * the previous note would light up during the lead-in), then whichever note was written last.
 */
fun highlightedNoteId(notes: List<Note>, recId: String?, positionMs: Long, focus: NoteFocus?): String? {
    if (focus != null && focus.recId == recId && notes.any { it.id == focus.noteId } &&
        // Small slack: right after a seek the player can report a position a frame short of the target.
        positionMs >= focus.fromMs - 500 && positionMs < focus.untilMs
    ) {
        return focus.noteId
    }
    return currentNoteId(notes, recId, positionMs)
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
