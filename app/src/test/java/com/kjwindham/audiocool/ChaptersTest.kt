package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.paragraphs
import com.kjwindham.audiocool.summarize.SummaryPrompts
import com.kjwindham.audiocool.summarize.cutAtTopics
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chapters in a talk without slides: cut where the talk turns to something else, each with a title. */
class ChaptersTest {
    @Test
    fun aLongTalkIsCutWhereItTurnsToSomethingElse() {
        // Ten paragraphs on sleep, then ten on caffeine, 60 words each: 1,200 words.
        val paras = paragraphs(talk(10, 10), emptyList())
        assertEquals(20, paras.size)
        val sections = cutAtTopics(paras, live = false)
        assertEquals(listOf(10, 10), sections.map { it.size })
        assertEquals(true, sections[1].first().text.contains("caffeine"))
    }

    @Test
    fun whileItsTranscribedACutWaitsForEnoughAndThenStaysPut() {
        // Not enough after the furthest place a cut could go: one open section.
        assertEquals(listOf(16), cutAtTopics(paragraphs(talk(10, 6), emptyList()), live = true).map { it.size })
        // Enough: cut at the turn, and more talk doesn't move it.
        assertEquals(10, cutAtTopics(paragraphs(talk(10, 8), emptyList()), live = true).first().size)
        assertEquals(10, cutAtTopics(paragraphs(talk(10, 14), emptyList()), live = true).first().size)
    }

    @Test
    fun aChaptersTitleAndSummaryAreReadFromTheReply() {
        assertEquals(
            "Caffeine and timing" to "Caffeine's half-life is five to six hours, so an afternoon coffee is still there at bedtime.",
            SummaryPrompts.parseChapter("Title: Caffeine and timing\nSummary: Caffeine's half-life is five to six hours, so an afternoon coffee is still there at bedtime."),
        )
        assertEquals(
            "Sleep cycles" to "Sleep comes in cycles of about ninety minutes, with more REM sleep toward morning.",
            SummaryPrompts.parseChapter("**Title:** \"Sleep cycles\"\n\n**Summary:** Sleep comes in cycles of about ninety minutes, with more REM sleep toward morning."),
        )
        // No title line: it's all summary.
        assertEquals(null to "Sleep comes in cycles of about ninety minutes.", SummaryPrompts.parseChapter("Sleep comes in cycles of about ninety minutes."))
    }

    /** [sleep] paragraphs about sleep, then [caffeine] about caffeine, 60 words each, a pause between. */
    private fun talk(sleep: Int, caffeine: Int): Recording {
        val sleepWords = "sleep cycles dream memory brain night rest deep REM tired".split(' ')
        val caffeineWords = "caffeine coffee espresso half-life tea cup afternoon jitters dose adenosine".split(' ')
        val segments = (0 until sleep + caffeine).map { i ->
            val pool = if (i < sleep) sleepWords else caffeineWords
            val text = (0 until 60).joinToString(" ") { j -> if (j % 3 == 0) pool[(i * 7 + j) % pool.size] else listOf("so", "the", "and", "we", "it")[j % 5] }
            TranscriptSegment(i * 30_000L, i * 30_000L + 25_000, text)
        }
        return Recording("r1", "a.m4a", 0, 3_600_000, transcript = segments)
    }
}
