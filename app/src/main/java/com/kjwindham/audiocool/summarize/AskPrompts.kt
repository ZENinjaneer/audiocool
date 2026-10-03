package com.kjwindham.audiocool.summarize

import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.util.timeLabel
import kotlin.math.ln

/** A moment an answer came from: where to play it, and its time on the timeline. */
data class Moment(val recId: String, val atMs: Long, val label: String)

/** An excerpt of a session to answer from: what was said, a note, or a photo's text, and its moment. */
data class Passage(val text: String, val moment: Moment, val kind: Kind) {
    enum class Kind { SAID, NOTE, PHOTO }
}

/**
 * Asking a question about a session: the excerpts most to do with it (by the words they share,
 * rarer words counting for more), and the summary model asked to answer from those alone, saying
 * which excerpts it used.
 */
object AskPrompts {
    const val ANSWER_TOKENS = 220

    /** At most this many excerpts, and this many words of them, so the phone can read it quickly. */
    private const val MAX_PASSAGES = 8
    private const val MAX_WORDS = 1_200

    /** Whether [query] reads as a question to answer rather than words to find. */
    fun looksLikeQuestion(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.endsWith("?")) return true
        val first = q.substringBefore(' ')
        return q.contains(' ') && first in setOf("what", "why", "how", "when", "who", "where", "which", "does", "did", "do", "is", "are", "was", "were", "can", "could", "should", "explain", "summarize", "summarise")
    }

    /** Everything in [session] that could answer a question: what was said (in paragraphs), notes and photo text. */
    fun passages(session: Session): List<Passage> = timelineRows(session, TimelineMode.EVERYTHING).mapNotNull { row ->
        when (row) {
            is TimelineRow.Speech -> moment(session, row.recId, row.startMs)?.let { Passage(row.text, it, Passage.Kind.SAID) }
            is TimelineRow.Written -> row.note.takeIf { it.text.isNotBlank() }?.let { n ->
                n.offsetMs?.let { moment(session, n.recId, it) }?.let { Passage(n.text, it, Passage.Kind.NOTE) }
            }
            is TimelineRow.Photo -> row.note.photoText?.takeIf { it.isNotBlank() }?.let { text ->
                row.note.offsetMs?.let { moment(session, row.note.recId, it) }?.let { Passage(text, it, Passage.Kind.PHOTO) }
            }
            else -> null
        }
    }

    /** The passages most to do with [question], in timeline order: words shared, rare ones counting for more. */
    fun relevant(passages: List<Passage>, question: String): List<Passage> {
        val terms = Words.content(question).toSet()
        if (terms.isEmpty()) return emptyList()
        val docs = passages.map { Words.all(it.text) }
        val df = terms.associateWith { t -> docs.count { t in it } }
        val scored = passages.indices.map { i ->
            val counts = docs[i].groupingBy { it }.eachCount()
            val length = docs[i].size.coerceAtLeast(1)
            // BM25, roughly: a term found in fewer passages says more; repeats help less and less.
            val score = terms.sumOf { t ->
                val tf = counts[t] ?: 0
                if (tf == 0) 0.0 else ln(1.0 + passages.size / (df.getValue(t) + 0.5)) * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / 60.0))
            }
            i to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val picked = ArrayList<Int>()
        var words = 0
        for ((i, _) in scored) {
            if (picked.size == MAX_PASSAGES || words + docs[i].size > MAX_WORDS && picked.isNotEmpty()) break
            picked += i
            words += docs[i].size
        }
        return picked.sorted().map { passages[it] }
    }

    /** Asks for an answer from [excerpts] (numbered from 1), with the session's summary for the gist. */
    fun prompt(session: Session, question: String, excerpts: List<Passage>): String = buildString {
        appendLine("You answer questions about a recorded talk or meeting, from what's below and nothing else.")
        session.summary?.text?.let {
            appendLine()
            appendLine("What it was about: $it")
        }
        appendLine()
        appendLine("Excerpts:")
        excerpts.forEachIndexed { i, p ->
            val what = when (p.kind) {
                Passage.Kind.SAID -> "said"
                Passage.Kind.NOTE -> "a note taken"
                Passage.Kind.PHOTO -> "on a slide"
            }
            appendLine("[${i + 1}] (${p.moment.label}, $what) ${p.text.trim()}")
        }
        appendLine()
        appendLine("Question: ${question.trim()}")
        appendLine()
        append(
            "Answer in two to four plain sentences. After each thing you say, put the number of the excerpt it " +
                "came from in brackets, like [2]. If the excerpts don't answer the question, say it isn't in this recording.",
        )
    }

    /** The answer in [reply], without its excerpt numbers, and the moments it cited, in order. */
    fun parse(reply: String, excerpts: List<Passage>): Pair<String, List<Moment>> {
        val cited = LinkedHashSet<Int>()
        val citation = Regex("""\[(\s*\d+\s*(?:[,;]\s*\d+\s*)*)]""")
        for (m in citation.findAll(reply)) {
            m.groupValues[1].split(',', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..excerpts.size }.forEach { cited += it }
        }
        val text = reply.replace(citation, "")
            .replace(Regex(" +([.,;:!?])"), "$1")
            .replace(Regex("[ \t]+"), " ")
            .trim()
            // ("it isn't in this recording." comes back as it was asked for.)
            .replaceFirstChar { it.uppercase() }
        return text to cited.map { excerpts[it - 1].moment }.distinct()
    }

    private fun moment(session: Session, recId: String?, atMs: Long): Moment? =
        recId?.let { id -> timeLabel(session, id, atMs)?.let { Moment(id, atMs, it) } }
}
