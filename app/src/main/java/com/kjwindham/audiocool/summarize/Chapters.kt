package com.kjwindham.audiocool.summarize

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.paragraphs
import com.kjwindham.audiocool.data.TimelineRow

/**
 * A part of a session to summarize on its own: a photo of a slide and what was said until the next
 * one, or without photos, a stretch of talk. A chapter longer than [SECTION_WORDS] is cut into
 * sections where the talk turns to something else, so each fits comfortably in what a phone-sized
 * model reads at once, and a talk without slides still comes in chapters.
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

/** A section is at least this long, unless it's all there is. */
const val MIN_SECTION_WORDS = 350

/** How many words either side of a break are compared, to tell whether the talk turns there. */
private const val WINDOW_WORDS = 120

/** How much a cut at the very start counts against it (similarity is 0 to 1): a real turn is a bigger drop. */
private const val EARLY_CUT = 0.15

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
        // Cut into sections where the talk turns to something else; while it's still being
        // transcribed, only from what's final, so a cut doesn't move.
        val sections = cutAtTopics(mine, live = settled != null).toMutableList<List<com.kjwindham.audiocool.data.TimelineRow.Speech>>()
        if (sections.isEmpty()) sections.add(emptyList())
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

/**
 * [paras] cut into sections of [MIN_SECTION_WORDS] to [SECTION_WORDS] words, each cut at the paragraph
 * break where the words just before and just after have least in common (TextTiling, roughly): where
 * the talk turns to something else. A cut is decided only once there's [WINDOW_WORDS] past the furthest
 * place it could go, from paragraphs that won't change ([live]: the last may still grow), so it stays put.
 */
internal fun cutAtTopics(paras: List<TimelineRow.Speech>, live: Boolean): List<List<TimelineRow.Speech>> {
    val sizes = paras.map { words(it.text) }
    val final = if (live) paras.size - 1 else paras.size
    val out = ArrayList<List<TimelineRow.Speech>>()
    var from = 0
    while (from < paras.size) {
        if (sizes.subList(from, paras.size).sum() <= SECTION_WORDS) {
            out += paras.subList(from, paras.size)
            break
        }
        // The paragraphs a cut can be judged by: final ones, to WINDOW_WORDS past the furthest cut.
        var reach = 0
        var until = from
        while (until < final && reach < SECTION_WORDS + WINDOW_WORDS) reach += sizes[until++]
        if (reach < SECTION_WORDS + WINDOW_WORDS) {
            // Not enough yet: one section, still open.
            out += paras.subList(from, paras.size)
            break
        }
        val cut = bestCut(paras, sizes, from, until)
        out += paras.subList(from, cut)
        from = cut
    }
    return out
}

/** Where to cut [from]..[until]: the break with least in common either side, among those far enough in. */
private fun bestCut(paras: List<TimelineRow.Speech>, sizes: List<Int>, from: Int, until: Int): Int {
    var best = -1
    var lowest = Double.MAX_VALUE
    var words = 0
    for (i in from until until - 1) {
        words += sizes[i]
        if (words < MIN_SECTION_WORDS) continue
        if (words > SECTION_WORDS) break
        // A clear turn wins; otherwise the later the better (fewer, fuller chapters).
        val score = alike(window(paras, (i downTo from).toList()), window(paras, (i + 1 until until).toList())) +
            EARLY_CUT * (SECTION_WORDS - words) / SECTION_WORDS
        if (score <= lowest) {
            lowest = score
            best = i + 1
        }
    }
    if (best >= 0) return best
    // No break in range (one very long paragraph, say): the first after SECTION_WORDS.
    words = 0
    for (i in from until until) {
        words += sizes[i]
        if (words >= SECTION_WORDS) return i + 1
    }
    return until
}

/** How often each content word comes up in about [WINDOW_WORDS] words of the paragraphs [order]ed out from a break. */
private fun window(paras: List<TimelineRow.Speech>, order: List<Int>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    var taken = 0
    for (i in order) {
        val ws = Words.content(paras[i].text)
        for (w in ws) counts[w] = (counts[w] ?: 0) + 1
        taken += words(paras[i].text)
        if (taken >= WINDOW_WORDS) break
    }
    return counts
}

private fun alike(a: Map<String, Int>, b: Map<String, Int>): Double {
    var dot = 0.0
    for ((w, n) in a) dot += n * (b[w] ?: 0)
    val na = kotlin.math.sqrt(a.values.sumOf { it.toDouble() * it })
    val nb = kotlin.math.sqrt(b.values.sumOf { it.toDouble() * it })
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
}
