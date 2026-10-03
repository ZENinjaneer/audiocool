package com.kjwindham.audiocool.data

import com.kjwindham.audiocool.speakers.Voices

/** What a session's timeline shows. */
enum class TimelineMode { EVERYTHING, CONTEXT, NOTES }

/** One row of a session's timeline, where notes, photos and what was said sit in the order they happened. */
sealed interface TimelineRow {
    val key: String

    /** Where a session with several recordings moves on to the next one. */
    data class RecordingStart(val rec: Recording, val number: Int) : TimelineRow {
        override val key get() = "rec:${rec.id}"
    }

    /** A stretch of what was said: transcript phrases run together up to a pause, a photo, a new speaker, or about 25 s. */
    data class Speech(
        val recId: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        /** Notes and ★ marks made during it, which come right after it. */
        val noteIds: List<String> = emptyList(),
        /** Shown only as the context of a note (in [TimelineMode.CONTEXT]). */
        val context: Boolean = false,
        /** Each word of [text], when what was said was timed word by word; else none. */
        val words: List<TimedWord> = emptyList(),
        /** Who said it (the session's voice id), once sorted by voice. */
        val voice: Int? = null,
    ) : TimelineRow {
        override val key get() = speechKey(recId, startMs)
    }

    /** A photo; it starts a chapter, as a new slide does in a talk. [number] counts the session's photos. */
    data class Photo(val note: Note, val number: Int) : TimelineRow {
        override val key get() = noteKey(note.id)
    }

    /** A typed or spoken note. */
    data class Written(val note: Note) : TimelineRow {
        override val key get() = noteKey(note.id)
    }

    /** A ★ moment. */
    data class Mark(val note: Note) : TimelineRow {
        override val key get() = noteKey(note.id)
    }

    /** A summary of the chapter starting here (after its photo, if it has one). */
    /**
     * A chapter's summary, where it starts; with its [title] and [number], shown as a heading when the
     * chapter has no slide to start it.
     */
    data class Summary(val chapterKey: String, val text: String, val title: String? = null, val number: Int = 0, val slide: Boolean = false) : TimelineRow {
        override val key get() = "summary:$chapterKey"
    }

    /** Talk left out of [TimelineMode.CONTEXT]; [firstKey] is its first paragraph in the full view. */
    data class Fold(val recId: String, val fromMs: Long, val skippedMs: Long, val firstKey: String) : TimelineRow {
        override val key get() = "fold:$recId:$fromMs"
    }
}

/** A word of a paragraph: when it starts in the recording, and where it is in the paragraph's text. */
data class TimedWord(val atMs: Long, val start: Int, val end: Int)

/** The word being said at [positionMs]: the last to have started (a moment early, as it's heard), or -1. */
fun currentWord(words: List<TimedWord>, positionMs: Long): Int = words.indexOfLast { it.atMs <= positionMs + 80 }

fun noteKey(noteId: String) = "note:$noteId"

fun speechKey(recId: String, startMs: Long) = "speech:$recId:$startMs"

/** A paragraph ends once adding the next phrase would take it past this... */
internal const val PARAGRAPH_MAX_MS = 25_000L

/** ...or at a pause this long. */
internal const val PARAGRAPH_PAUSE_MS = 2_000L

/** [rec]'s transcript run together into paragraphs, broken at pauses, at about 25 s, and at each time in [breaks]. */
fun paragraphs(rec: Recording, breaks: List<Long>): List<TimelineRow.Speech> {
    val out = ArrayList<TimelineRow.Speech>()
    val text = StringBuilder()
    val timed = ArrayList<TimedWord>()
    var allTimed = true
    var start = 0L
    var end = 0L
    var voice: Int? = null
    fun flush() {
        // Word by word only when every phrase in it was timed so, or the wrong word could light up.
        if (text.isNotEmpty()) out += TimelineRow.Speech(rec.id, start, end, text.toString(), words = if (allTimed) timed.toList() else emptyList(), voice = voice)
        text.clear()
        timed.clear()
        allTimed = true
    }
    // Phrases that more than one person spoke in, cut where the speaker changes.
    for (segment in rec.transcript.orEmpty().flatMap { Voices.splitBySpeaker(it, rec.speakers) }) {
        val words = segment.text.trim()
        if (words.isEmpty()) continue
        val speaker = Voices.voiceAt(rec.speakers, segment.startMs, segment.endMs)
        if (text.isNotEmpty() && (
                segment.startMs - end >= PARAGRAPH_PAUSE_MS ||
                    segment.endMs - start > PARAGRAPH_MAX_MS ||
                    breaks.any { it > start && it <= segment.startMs } ||
                    (speaker != null && speaker != voice)
                )
        ) {
            flush()
        }
        if (text.isEmpty()) {
            start = segment.startMs
            voice = speaker
        } else {
            text.append(' ')
        }
        val pieces = words.split(' ')
        val offsets = segment.words
        if (offsets != null && offsets.size == pieces.size) {
            var at = text.length
            pieces.forEachIndexed { i, piece ->
                timed += TimedWord(segment.startMs + offsets[i], at, at + piece.length)
                at += piece.length + 1
            }
        } else {
            allTimed = false
        }
        text.append(words)
        end = segment.endMs
    }
    flush()
    return out
}

/**
 * The session as one timeline: each recording's paragraphs in order, every note right after the
 * paragraph it was written during, and photos where they were taken (a paragraph never runs past a
 * photo). Notes not linked to the audio fit in by when they were written. [mode] picks how much of
 * what was said to show: all of it, only the paragraphs that notes were written during (the rest
 * folded away), or none.
 */
fun timelineRows(session: Session, mode: TimelineMode): List<TimelineRow> {
    class Placed(val time: Long, val order: Int, val tie: Long, val row: TimelineRow)

    val placed = ArrayList<Placed>()
    val notes = session.orderedNotes()
    val several = session.recordings.size > 1
    session.recordings.forEachIndexed { index, rec ->
        if (several) placed += Placed(rec.createdAt, -1, 0, TimelineRow.RecordingStart(rec, index + 1))
        val inRec = notes.filter { it.recId == rec.id && it.offsetMs != null }
        val photoTimes = inRec.filter { it.photo != null }.map { it.offsetMs!! }
        val paras = paragraphs(rec, photoTimes)
        val notesIn = HashMap<String, MutableList<String>>()
        for (note in inRec) {
            val at = note.offsetMs!!
            if (note.photo != null) {
                placed += Placed(rec.createdAt + at, 0, note.createdAt, TimelineRow.Photo(note, 0))
                continue
            }
            // After the paragraph it was written during, unless a photo came between the two.
            val para = paras.lastOrNull { it.startMs <= at }?.takeIf { p -> photoTimes.none { it > p.startMs && it <= at } }
            if (para != null) notesIn.getOrPut(para.key) { ArrayList() } += note.id
            placed += Placed(rec.createdAt + (para?.startMs ?: at), 2, at, note.toRow())
        }
        for (p in paras) placed += Placed(rec.createdAt + p.startMs, 1, p.startMs, p.copy(noteIds = notesIn[p.key].orEmpty()))
    }
    // Each chapter's summary, where the chapter starts: after its photo, before what was said. (Only with
    // several chapters; a session that's one chapter has just its summary at the top.)
    val chapterList = com.kjwindham.audiocool.summarize.chapters(session) { null }.filterNot { it.slight }
    if (chapterList.size > 1) {
        chapterList.forEachIndexed { i, c ->
            val summary = session.chapterSummaries.firstOrNull { it.key == c.key }?.takeIf { it.text.isNotBlank() } ?: return@forEachIndexed
            val rec = session.recording(c.recId) ?: return@forEachIndexed
            placed += Placed(rec.createdAt + c.startMs, 0, Long.MAX_VALUE, TimelineRow.Summary(c.key, summary.text, summary.title, i + 1, slide = c.photo != null))
        }
    }
    for (note in notes) {
        if (note.offsetMs != null && session.recording(note.recId) != null) continue
        val row = if (note.photo != null) TimelineRow.Photo(note, 0) else note.toRow()
        placed += Placed(note.createdAt, if (note.photo != null) 0 else 2, note.createdAt, row)
    }
    placed.sortWith(compareBy<Placed>({ it.time }, { it.order }, { it.tie }))

    var photos = 0
    val rows = placed.map { p -> (p.row as? TimelineRow.Photo)?.copy(number = ++photos) ?: p.row }
    return when (mode) {
        TimelineMode.EVERYTHING -> rows
        TimelineMode.NOTES -> rows.filter { it !is TimelineRow.Speech }
        TimelineMode.CONTEXT -> {
            // Paragraphs without notes fold away; a run of them becomes one Fold, placed before whatever is shown next.
            val out = ArrayList<TimelineRow>()
            var foldFrom: TimelineRow.Speech? = null
            var skipped = 0L
            fun flush() {
                foldFrom?.let { out += TimelineRow.Fold(it.recId, it.startMs, skipped, it.key) }
                foldFrom = null
                skipped = 0
            }
            for (row in rows) {
                if (row is TimelineRow.Speech && row.noteIds.isEmpty()) {
                    if (foldFrom == null) foldFrom = row
                    skipped += row.endMs - row.startMs
                } else {
                    flush()
                    out += if (row is TimelineRow.Speech) row.copy(context = true) else row
                }
            }
            flush()
            out
        }
    }
}

private fun Note.toRow(): TimelineRow = if (isMark) TimelineRow.Mark(this) else TimelineRow.Written(this)

/**
 * Where something a search found sits in [rows]: a note's or photo's own row, a chapter's summary, or the
 * paragraph with what was said at [atMs] of [recId]. Null if [rows] leaves it out, as the notes-only view
 * does what was said.
 */
fun foundRowKey(rows: List<TimelineRow>, noteId: String?, recId: String?, atMs: Long?, chapterKey: String? = null): String? = when {
    chapterKey != null -> "summary:$chapterKey".takeIf { key -> rows.any { it.key == key } } ?: foundRowKey(rows, null, recId, atMs)
    noteId != null -> noteKey(noteId).takeIf { key -> rows.any { it.key == key } }
    recId != null && atMs != null -> rows.firstOrNull { it is TimelineRow.Speech && it.recId == recId && atMs in it.startMs..it.endMs }?.key
    else -> null
}

/** The paragraph playing at [positionMs] of [recId]: the last one that's started (a moment early, as a tap starts just before it). */
fun playingSpeechKey(rows: List<TimelineRow>, recId: String?, positionMs: Long): String? =
    rows.lastOrNull { it is TimelineRow.Speech && it.recId == recId && it.startMs <= positionMs + 400 }?.key
