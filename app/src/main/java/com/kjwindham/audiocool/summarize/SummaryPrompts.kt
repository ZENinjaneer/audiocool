package com.kjwindham.audiocool.summarize

import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.ui.photoTitle
import com.kjwindham.audiocool.util.timeLabel
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * What the summary model is asked, and reading its replies. Worded and checked against Gemma 4 E2B:
 * asked for JSON in plain words it replies in JSON (in a code fence); forced to with constrained
 * decoding, this small model loops on whitespace instead.
 */
object SummaryPrompts {
    const val SYSTEM = "You summarize recorded talks, lectures and meetings for the person who recorded them. " +
        "Use only what's in the material. Be concrete: keep names, numbers and claims. No preamble."

    const val CHAPTER_TOKENS = 140
    const val SESSION_TOKENS = 450

    private const val ASK_JSON = "Reply as JSON with title (at most 8 words), summary (2 to 4 sentences), " +
        "keyPoints (3 to 6 short points), actionItems (things the listener should do, from the talk or their notes; empty if none)."

    // About what the model reads comfortably at once on a phone, with room for the reply.
    private const val SESSION_WORDS = 2_200

    fun chapter(c: Chapter): String = buildString {
        append(if (c.continued) "Give this part of the talk (it continues the part before)" else "Give this part of the talk")
        appendLine(" a short title of 2 to 6 words, and summarize it in 1 to 3 sentences, at most 60 words.")
        appendLine("Reply as two lines: Title: ... and Summary: ...")
        appendLine()
        appendMaterial(c)
    }

    /** The title and summary in a reply to [chapter]; a reply without the two lines is all summary. */
    fun parseChapter(reply: String): Pair<String?, String>? {
        val lines = reply.trim().removeSurrounding("```").lines().map { it.replace("**", "").trim() }.filter { it.isNotEmpty() }
        val titleLine = lines.firstOrNull { it.startsWith("title:", ignoreCase = true) }
        val title = titleLine?.substringAfter(':')?.trim()?.trim('"', '“', '”', '.')?.takeIf { it.isNotEmpty() && it.length <= 60 }
        val rest = lines.filter { it !== titleLine }.joinToString(" ")
        val summary = cleanChapter(rest) ?: return null
        return title to summary
    }

    /**
     * The whole session: from its chapters' summaries ([summaries], in chapter order), or when it's a
     * single chapter, from that chapter itself.
     */
    fun session(session: Session, chapters: List<Chapter>, summaries: Map<String, String>): String = buildString {
        val notes = session.orderedNotes().filter { it.photo == null && !it.isMark }.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (chapters.size == 1) {
            appendLine("Here is a recorded session: what was said, and the notes the listener wrote.")
            appendLine()
            appendMaterial(chapters.single())
        } else {
            appendLine("Here are summaries of each part of a recorded session, in order, and the notes the listener wrote.")
            appendLine()
            val parts = chapters.mapNotNull { c ->
                val text = summaries[c.key] ?: return@mapNotNull null
                val at = timeLabel(session, c.recId, c.startMs)
                val slide = c.photo?.let(::photoTitle)?.let { ", slide \"$it\"" }.orEmpty()
                "($at$slide)" to text
            }
            // A long session: keep each part to its first sentence so it all fits.
            val long = parts.sumOf { words(it.second) } > SESSION_WORDS
            parts.forEachIndexed { i, (where, text) ->
                appendLine("Part ${i + 1} $where: ${if (long) firstSentence(text) else text}")
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("Notes:")
                notes.take(40).forEach { appendLine("- $it") }
            }
        }
        appendLine()
        append(ASK_JSON)
    }

    private fun StringBuilder.appendMaterial(c: Chapter) {
        c.photo?.textInPhoto?.let {
            appendLine("Slide (photo of the screen):")
            appendLine(it)
            appendLine()
        }
        if (c.notes.isNotEmpty()) {
            appendLine("The listener's notes:")
            c.notes.forEach { appendLine("- ${it.text.trim()}") }
            appendLine()
        }
        if (c.speech.isNotBlank()) {
            appendLine("What was said:")
            appendLine(c.speech)
        }
    }

    /** A chapter summary as shown: the reply without any label, markdown or line breaks. */
    fun cleanChapter(reply: String): String? =
        reply.trim()
            .removeSurrounding("```")
            .replace(Regex("^(summary|here is a summary[^:]*):\\s*", RegexOption.IGNORE_CASE), "")
            .replace("**", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.count(Char::isLetter) >= 10 }

    data class Parsed(val title: String?, val summary: String, val keyPoints: List<String>, val actionItems: List<String>)

    /** The session reply: its JSON, wherever it is in the reply; failing that, the reply as a plain summary. */
    fun parseSession(reply: String): Parsed? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching {
                val o = JSONObject(reply.substring(start, end + 1))
                val summary = o.optString("summary").trim()
                if (summary.isNotEmpty()) {
                    return Parsed(
                        title = o.optString("title").trim().trim('"').takeIf { it.isNotEmpty() && it.length <= 80 },
                        summary = summary,
                        keyPoints = o.optJSONArray("keyPoints").texts(),
                        actionItems = o.optJSONArray("actionItems").texts(),
                    )
                }
            }
        }
        return cleanChapter(reply.substringBefore('{'))?.let { Parsed(null, it, emptyList(), emptyList()) }
    }

    private fun JSONArray?.texts(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it).trim().removePrefix("- ").trim() }
            .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
            .take(8)
    }

    private fun firstSentence(text: String): String = text.split(Regex("(?<=[.!?])\\s+"), limit = 2).first()

    /** A short fingerprint of what a summary was made from. */
    fun basis(prompt: String): String =
        MessageDigest.getInstance("SHA-1").digest(prompt.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
