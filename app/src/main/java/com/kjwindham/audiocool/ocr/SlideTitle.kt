package com.kjwindham.audiocool.ocr

import kotlin.math.abs

/** A line of text found in a photo: its box in pixels, and the height of its letters. */
data class TextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Letter height, measured along the text (the box is taller than the text when it's tilted). */
    val height: Int = bottom - top,
    val confidence: Float = 1f,
)

/**
 * Picks the title out of the text found in a photo of a slide: the biggest text, which on a slide is
 * the title, together with any line above or below it at the same size (a title on two lines). Text
 * that can't be a title is passed over: text the reader isn't sure of, slide numbers and dates, web
 * addresses, and a lone short word (an EXIT sign behind the screen). A slide whose text is all the
 * same size has no title.
 */
object SlideTitle {
    private const val MAX_LENGTH = 100
    private val ADDRESS = Regex("""https?://|www\.|@|\.(com|org|net|io|edu|gov|ai|dev)\b""", RegexOption.IGNORE_CASE)

    // Misread logos tend to have stray symbols inside words, like "WV(IMANIA".
    private val STRAY_SYMBOL = Regex("""\p{L}[^\p{L}\p{N}\s'’.,:;!?&/+#\-–—‐‑]\p{L}""")

    fun pick(lines: List<TextLine>): String? {
        val usable = joinRows(lines).filter(::couldBeTitle)
        val best = usable.maxWithOrNull(compareBy<TextLine>({ it.height }, { -it.top })) ?: return null
        // A title (on up to three lines) is bigger than the rest; more lines that size are just body text.
        if (usable.count { it.height >= best.height * 3 / 4 } > 3) return null

        val title = mutableListOf(best)
        fun sameTitle(line: TextLine, next: TextLine): Boolean {
            val gap = if (next.top >= line.top) next.top - line.bottom else line.top - next.bottom
            val overlap = minOf(line.right, next.right) - maxOf(line.left, next.left)
            return next.height in (best.height * 3 / 4)..(best.height * 4 / 3) &&
                gap in (-best.height / 2)..(best.height * 4 / 5) &&
                overlap >= minOf(line.right - line.left, next.right - next.left) * 3 / 10
        }
        while (title.size < 3) {
            val below = usable.filter { it !in title && it.top > title.last().top && sameTitle(title.last(), it) }.minByOrNull { it.top }
            val above = usable.filter { it !in title && it.top < title.first().top && sameTitle(title.first(), it) }.maxByOrNull { it.top }
            when {
                below != null -> title += below
                above != null -> title.add(0, above)
                else -> break
            }
        }
        return clean(title.joinToString(" ") { it.text })
    }

    /** Joins pieces of one line that were read separately (big titles can come word by word). */
    private fun joinRows(lines: List<TextLine>): List<TextLine> {
        val rows = mutableListOf<MutableList<TextLine>>()
        for (line in lines.sortedBy { it.left }) {
            val row = rows.firstOrNull { r ->
                val last = r.last()
                val size = maxOf(last.height, line.height)
                abs((line.top + line.bottom) - (last.top + last.bottom)) / 2 <= size / 3 && // level with it
                    line.left - last.right in (-size / 2)..size && // just after it
                    line.height * 3 >= last.height * 2 && last.height * 3 >= line.height * 2 // about the same size
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.map { r ->
            if (r.size == 1) {
                r[0]
            } else {
                TextLine(
                    r.joinToString(" ") { it.text.trim() },
                    r.minOf { it.left },
                    r.minOf { it.top },
                    r.maxOf { it.right },
                    r.maxOf { it.bottom },
                    r.maxOf { it.height },
                    r.minOf { it.confidence }, // as sure as its least sure piece
                )
            }
        }
    }

    private fun couldBeTitle(line: TextLine): Boolean {
        val text = line.text.trim()
        val letters = text.count(Char::isLetter)
        val visible = text.count { !it.isWhitespace() }
        return line.confidence >= 0.75f &&
            letters >= 4 &&
            letters * 2 >= visible && // mostly words, not numbers or symbols
            (text.contains(' ') || letters >= 6) && // a lone short word is more likely a sign
            !ADDRESS.containsMatchIn(text) &&
            !STRAY_SYMBOL.containsMatchIn(text)
    }

    private fun clean(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('•', '·', '-', '–', '—', '*', '"', '“', '\'', ' ')
            .trimEnd(':', ';', ',', '-', '–', '—', '"', '”', ' ')
        if (text.isEmpty()) return null
        if (text.length <= MAX_LENGTH) return text
        val cut = text.take(MAX_LENGTH)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > MAX_LENGTH / 2 } ?: cut.length).trimEnd() + "…"
    }
}
