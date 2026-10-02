package com.kjwindham.audiocool.summarize

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.paragraphs

/**
 * A part of a session to summarize on its own: a photo of a slide and what was said until the next
 * one, or without photos, a stretch of talk. A chapter longer than [SECTION_WORDS] is cut into
 * sections at paragraphs, so each fits comfortably in what a phone-sized model reads at once.
 */
data class Chapter(
    val key: String,
    val recId: String,
    val startMs: Long,
    val endMs: Long,
    /** The slide that starts it; null for talk before the first photo, and for a long chapter's later sections. */
    val photo: Note?,
    /** A later section of a long chapter. */
    val continued: Boolean,
    val speech: String,
    /** Notes written during it (not photos or ★ marks). */
    val notes: List<Note>,
    /** Nothing more will be added to it, so it can be summarized. */
    val complete: Boolean,
) {
    /** Too little in it to be worth a summary. */
    val slight: Boolean get() = words(speech) < 30 && notes.isEmpty() && photo?.textInPhoto == null
}

const val SECTION_WORDS = 900

fun chapterKey(recId: String, startMs: Long) = "chapter:$recId:$startMs"

/** The recording and start time a [chapterKey] names, or null if [key] isn't one. */
fun parseChapterKey(key: String): Pair<String, Long>? {
    val rest = key.removePrefix("chapter:").takeIf { it != key } ?: return null
    val startMs = rest.substringAfterLast(':', "").toLongOrNull() ?: return null
    return rest.substringBeforeLast(':') to startMs
}

/**
 * The session's chapters, in order. [settledUntil] says how much of a recording's transcript is final:
 * null when all of it is; while it's still being transcribed live, how far that's got (a chapter is
 * complete once the transcript is past the start of the next); -1 when it isn't transcribed yet.
 */
fun chapters(session: Session, settledUntil: (Recording) -> Long?): List<Chapter> =
    session.recordings.flatMap { rec -> chaptersOf(rec, session.notes, settledUntil(rec)) }

private fun chaptersOf(rec: Recording, allNotes: List<Note>, settled: Long?): List<Chapter> {
    if (settled == -1L || rec.transcript == null) return emptyList()
    val notes = allNotes.filter { it.recId == rec.id && it.offsetMs != null }.sortedBy { it.offsetMs }
    val photos = notes.filter { it.photo != null }
    val paras = paragraphs(rec, photos.map { it.offsetMs!! })

    // Chapters start at each photo (and at the start, for talk before the first one).
    class Raw(val startMs: Long, val photo: Note?)
    val starts = buildList {
        if (photos.isEmpty() || paras.any { it.startMs < photos.first().offsetMs!! }) add(Raw(0, null))
        photos.forEach { add(Raw(it.offsetMs!!, it)) }
    }
    val out = ArrayList<Chapter>()
    starts.forEachIndexed { i, raw ->
        val until = starts.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        // A paragraph belongs where it starts; the one under way when a photo was taken stays before it.
        val mine = paras.filter { it.startMs >= raw.startMs && it.startMs < until }
        val written = notes.filter { it.photo == null && !it.isMark && it.offsetMs!! >= raw.startMs && it.offsetMs < until }
        // Cut into sections of about SECTION_WORDS, at paragraphs.
        val sections = ArrayList<MutableList<com.kjwindham.audiocool.data.TimelineRow.Speech>>()
        for (p in mine) {
            val current = sections.lastOrNull()
            if (current == null || current.sumOf { words(it.text) } >= SECTION_WORDS) sections += mutableListOf(p) else current += p
        }
        if (sections.isEmpty()) sections.add(mutableListOf())
        val nextStart = starts.getOrNull(i + 1)?.startMs
        sections.forEachIndexed { j, part ->
            val start = if (j == 0) raw.startMs.coerceAtMost(part.firstOrNull()?.startMs ?: raw.startMs) else part.first().startMs
            val end = part.lastOrNull()?.endMs ?: start
            val sectionEnd = sections.getOrNull(j + 1)?.first()?.startMs ?: until
            val complete = when {
                settled == null -> true
                j < sections.lastIndex -> true // a later section has started, so this one is full
                nextStart != null -> settled >= nextStart
                else -> false // the end of a recording still going
            }
            out += Chapter(
                key = chapterKey(rec.id, if (j == 0) raw.startMs else start),
                recId = rec.id,
                startMs = if (j == 0) raw.startMs else start,
                endMs = end,
                photo = if (j == 0) raw.photo else null,
                continued = j > 0,
                speech = part.joinToString(" ") { it.text },
                notes = written.filter { it.offsetMs!! >= (if (j == 0) raw.startMs else start) && it.offsetMs < sectionEnd },
                complete = complete,
            )
        }
    }
    return out
}

internal fun words(text: String) = text.split(' ', '\n').count { it.isNotBlank() }
