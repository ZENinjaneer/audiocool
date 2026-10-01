package com.kjwindham.audiocool.search

import com.kjwindham.audiocool.data.Session
import java.text.Normalizer

enum class HitKind { NOTE, SPEECH, PHOTO }

data class SearchHit(
    val sessionId: String,
    val kind: HitKind,
    val text: String,
    /** Where the query's words are in [text], for highlighting. */
    val matches: List<IntRange>,
    val recId: String? = null,
    /** Position in the recording; null for a note that isn't linked to audio. */
    val atMs: Long? = null,
    val noteId: String? = null,
    /** Orders hits along the session's timeline, like its notes. */
    val timelineKey: Long = 0,
)

/** Notes, text on photos, and stretches of speech in [session] that contain every word of [query], in timeline order. */
fun searchSession(session: Session, query: String): List<SearchHit> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return emptyList()
    val hits = ArrayList<SearchHit>()
    for (note in session.notes) {
        val matches = findTerms(note.text, terms) ?: continue
        val rec = session.recording(note.recId)
        val offset = note.offsetMs
        val linked = rec != null && offset != null
        hits += SearchHit(
            sessionId = session.id,
            kind = HitKind.NOTE,
            text = note.text,
            matches = matches,
            recId = rec?.id,
            atMs = if (linked) offset else null,
            noteId = note.id,
            timelineKey = if (rec != null && offset != null) rec.createdAt + offset else note.createdAt,
        )
    }
    // Text on the photos: show the lines that matched.
    for (note in session.notes) {
        val text = note.photoText ?: continue
        if (findTerms(text, terms) == null) continue
        val snippet = text.lines().filter { line -> terms.any { fold(line).contains(it) } }.take(3).joinToString(" · ")
        val (shown, matches) = findTerms(snippet, terms)?.let { snippet to it } ?: text.replace("\n", " · ").let { it to findTerms(it, terms)!! }
        val rec = session.recording(note.recId)
        val offset = note.offsetMs
        hits += SearchHit(
            sessionId = session.id,
            kind = HitKind.PHOTO,
            text = shown,
            matches = matches,
            recId = rec?.id,
            atMs = if (rec != null) offset else null,
            noteId = note.id,
            timelineKey = if (rec != null && offset != null) rec.createdAt + offset else note.createdAt,
        )
    }
    for (rec in session.recordings) {
        for (segment in rec.transcript.orEmpty()) {
            val matches = findTerms(segment.text, terms) ?: continue
            hits += SearchHit(session.id, HitKind.SPEECH, segment.text, matches, rec.id, segment.startMs, null, rec.createdAt + segment.startMs)
        }
    }
    return hits.sortedBy { it.timelineKey }
}

/** Searches every session, newest first. */
fun searchAll(sessions: List<Session>, query: String, limit: Int = 500): List<SearchHit> =
    sessions.sortedByDescending { it.createdAt }.asSequence().flatMap { searchSession(it, query) }.take(limit).toList()

fun searchTerms(query: String): List<String> = fold(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }.distinct()

/** Every occurrence of every term in [text], merged into ranges, or null if any term is missing. */
fun findTerms(text: String, terms: List<String>): List<IntRange>? {
    val folded = fold(text)
    val ranges = ArrayList<IntRange>()
    for (term in terms) {
        var from = 0
        var found = false
        while (true) {
            val i = folded.indexOf(term, from)
            if (i < 0) break
            ranges += i until i + term.length
            found = true
            from = i + term.length
        }
        if (!found) return null
    }
    ranges.sortBy { it.first }
    val merged = ArrayList<IntRange>()
    for (r in ranges) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged += r
    }
    return merged
}

/** Lowercase with accents removed, one character per character so match positions map back onto [s]. */
fun fold(s: String): String {
    val out = CharArray(s.length)
    for (i in s.indices) {
        val ch = s[i]
        out[i] = if (ch.code < 128) {
            ch.lowercaseChar()
        } else {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .lowercase()
            if (base.length == 1) base[0] else ch.lowercaseChar()
        }
    }
    return String(out)
}
