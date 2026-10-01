package com.kjwindham.audiocool

import com.kjwindham.audiocool.ocr.SlideTitle
import com.kjwindham.audiocool.ocr.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideTitleTest {
    /** A line of text [height] px tall whose top-left corner is at ([x], [y]). */
    private fun line(text: String, x: Int, y: Int, height: Int, width: Int = text.length * height / 2, confidence: Float = 0.9f) =
        TextLine(text, x, y, x + width, y + height, height, confidence)

    @Test
    fun theBiggestTextIsTheTitle() {
        val lines = listOf(
            line("Scaling Inference on the Edge", 200, 300, 90),
            line("Dana Ruiz · Platform Team", 300, 480, 40),
            line("October 1, 2026", 300, 540, 36),
        )
        assertEquals("Scaling Inference on the Edge", SlideTitle.pick(lines))
    }

    @Test
    fun aTitleOnTwoLinesIsJoined() {
        val lines = listOf(
            line("What We Learned Running", 200, 200, 80),
            line("Postgres at Petabyte Scale", 220, 300, 82),
            line("Lessons from five years of growth", 240, 460, 40),
        )
        assertEquals("What We Learned Running Postgres at Petabyte Scale", SlideTitle.pick(lines))
    }

    @Test
    fun signsAddressesAndNumbersAreNotTitles() {
        val lines = listOf(
            // The room around the screen: a big EXIT sign.
            line("EXIT", 40, 20, 140),
            line("Observability 101", 300, 260, 70),
            line("• Metrics, logs and traces", 300, 420, 38),
            line("• Sampling without losing the story", 300, 470, 38),
            line("example.com/talks/o11y", 300, 900, 30),
            line("12 / 48", 1500, 900, 30),
        )
        assertEquals("Observability 101", SlideTitle.pick(lines))
        assertNull(SlideTitle.pick(listOf(line("https://conf.example.org", 100, 100, 90), line("2026", 100, 300, 120))))
    }

    @Test
    fun aTitleReadWordByWordIsPutBackTogether() {
        // As an OCR engine read a real slide: one box per word, sizes varying with the letters' shapes.
        val lines = listOf(
            TextLine("Making", 268, 280, 739, 407, 108, 1f),
            TextLine("easy", 770, 309, 1063, 403, 86, 1f),
            TextLine("ways", 1076, 306, 1417, 403, 91, 0.99f),
            TextLine("for", 1431, 289, 1618, 390, 97, 1f),
            TextLine("technical", 798, 431, 1392, 530, 99, 1f),
            TextLine("new", 519, 452, 791, 526, 70, 1f),
            TextLine("contributors", 561, 574, 1348, 679, 105, 1f),
            TextLine("WIKIMANIA", 760, 60, 1070, 100, 37, 0.91f),
        )
        assertEquals("Making easy ways for new technical contributors", SlideTitle.pick(lines))
    }

    @Test
    fun aSlideOfSameSizeBulletsHasNoTitle() {
        val lines = listOf(
            line("Keep pull requests small", 200, 200, 40),
            line("Review within one day", 200, 260, 41),
            line("Automate the boring checks", 200, 320, 40),
            line("Write the test first", 200, 380, 39),
        )
        assertNull(SlideTitle.pick(lines))
    }

    @Test
    fun unsureTextAndPunctuationAreDropped() {
        assertEquals("Roadmap for 2027", SlideTitle.pick(listOf(line("“Roadmap for 2027:", 100, 100, 80), line("Garbled tiny noise", 100, 400, 90, confidence = 0.5f))))
        // A blurry photo's misread text isn't worth renaming a session after.
        assertNull(SlideTitle.pick(listOf(line("GUETILLA", 100, 100, 80, confidence = 0.53f))))
        assertNull(SlideTitle.pick(listOf(line("WV(IMANIA", 100, 100, 80, confidence = 0.86f))))
        assertEquals("AT&T: Re:Invent / I/O", SlideTitle.pick(listOf(line("AT&T: Re:Invent / I/O", 100, 100, 80))))
        assertNull(SlideTitle.pick(emptyList()))
    }

    @Test
    fun aVeryLongTitleIsShortenedAtAWord() {
        val long = "A Practical Guide to Building Reliable Distributed Systems When Everything You Depend On Is Eventually Consistent and Occasionally Wrong"
        val title = SlideTitle.pick(listOf(line(long, 50, 100, 60), line("Sam Lee", 50, 300, 30)))!!
        assertEquals(true, title.length <= 101 && title.endsWith("…") && !title.contains("  "))
    }
}
