package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.MARK_TEXT
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.summarize.SECTION_WORDS
import com.kjwindham.audiocool.summarize.SummaryPrompts
import com.kjwindham.audiocool.summarize.chapterKey
import com.kjwindham.audiocool.summarize.chapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cutting sessions into chapters, asking the model about them, and reading what it says. */
class SummaryTest {
    private fun seg(fromSec: Double, toSec: Double, text: String) = TranscriptSegment((fromSec * 1000).toLong(), (toSec * 1000).toLong(), text)

    private val talk = listOf(
        seg(0.5, 8.0, "Thanks for coming."),
        seg(8.5, 17.0, "Today: models on phones."),
        seg(17.5, 27.0, "They used to be too big."),
        seg(30.0, 38.0, "What changed?"),
        seg(38.5, 44.0, "Quantization."),
        seg(44.5, 52.0, "Eight bits per weight."),
        seg(52.5, 60.0, "Smaller is faster."),
        seg(66.0, 75.0, "Then calibration."),
    )
    private val rec = Recording("r1", "recording-1.m4a", createdAt = 1_000_000, durationMs = 80_000, transcript = talk)
    private val slide = Note("p1", "", 7, "r1", 41_000, photo = "photo-p1.jpg", photoText = "Quantization 101\n8-bit and 4-bit weights")
    private val session = Session(
        "s", "Talk", createdAt = 0, updatedAt = 0, recordings = listOf(rec),
        notes = listOf(
            Note("n1", "Too big: memory, battery", 5, "r1", 20_000),
            slide,
            Note("m1", MARK_TEXT, 8, "r1", 50_000),
            Note("p2", "", 9, "r1", 63_000, photo = "photo-p2.jpg"),
            Note("n2", "Look up calibration", 10, "r1", 70_000, spoken = true),
        ),
    )

    @Test
    fun aChapterIsASlideAndWhatWasSaidUntilTheNext() {
        val chs = chapters(session) { null }
        assertEquals(listOf(chapterKey("r1", 0), chapterKey("r1", 41_000), chapterKey("r1", 63_000)), chs.map { it.key })
        assertEquals("Thanks for coming. Today: models on phones. They used to be too big. What changed? Quantization.", chs[0].speech)
        assertEquals(listOf("n1"), chs[0].notes.map { it.id })
        assertEquals(slide, chs[1].photo)
        // ★ marks aren't notes to summarize; the spoken note is.
        assertEquals(emptyList<String>(), chs[1].notes.map { it.id })
        assertEquals("Eight bits per weight. Smaller is faster.", chs[1].speech)
        assertEquals(listOf("n2"), chs[2].notes.map { it.id })
        assertTrue(chs.all { it.complete })
        // A photo with no text and little said under it still counts, for its note.
        assertFalse(chs[2].slight)
    }

    @Test
    fun whileTranscribingLiveAChapterIsCompleteOnceTheTranscriptPassesTheNextSlide() {
        val live = chapters(session) { 50_000 }
        assertEquals(listOf(true, false, false), live.map { it.complete })
        assertEquals(listOf(true, true, false), chapters(session) { 64_000 }.map { it.complete })
        // Not transcribed yet: nothing to summarize.
        assertTrue(chapters(session) { -1 }.isEmpty())
        assertTrue(chapters(session.copy(recordings = listOf(rec.copy(transcript = null)))) { null }.isEmpty())
    }

    @Test
    fun aLongChapterIsCutIntoSections() {
        // Twelve minutes on one slide: phrases of 25 words, a 3 s pause between each.
        val long = (0 until 60).map { i -> seg(i * 12.0, i * 12.0 + 9, (1..25).joinToString(" ") { "word$it" }) }
        val s = session.copy(recordings = listOf(rec.copy(transcript = long)), notes = emptyList())
        val chs = chapters(s) { null }
        // 1,500 words: a full section of 900, and the 600 after it.
        assertEquals(listOf(900, 600), chs.map { it.speech.split(' ').size })
        assertEquals(listOf(false, true), chs.map { it.continued })
        assertTrue(SECTION_WORDS == 900)
        // Live, the first section is full, so it's complete; the last is still growing.
        assertEquals(listOf(true, false), chapters(s) { 10_000_000 }.map { it.complete })
    }

    @Test
    fun theModelIsAskedAboutAChapterWithItsSlideNotesAndWords() {
        val prompt = SummaryPrompts.chapter(chapters(session) { null }[1])
        assertTrue(prompt.startsWith("Give this part of the talk a short title of 2 to 6 words, and summarize it in 1 to 3 sentences, at most 60 words."))
        assertTrue(prompt.contains("Slide (photo of the screen):\nQuantization 101\n8-bit and 4-bit weights"))
        assertTrue(prompt.contains("What was said:\nEight bits per weight. Smaller is faster."))
        assertFalse(prompt.contains("listener's notes"))
        assertTrue(SummaryPrompts.chapter(chapters(session) { null }[0]).contains("The listener's notes:\n- Too big: memory, battery"))
        // Same chapter, same prompt, same fingerprint; any change, a new one.
        assertEquals(SummaryPrompts.basis(prompt), SummaryPrompts.basis(SummaryPrompts.chapter(chapters(session) { null }[1])))
        assertFalse(SummaryPrompts.basis(prompt) == SummaryPrompts.basis(prompt + "!"))
    }

    @Test
    fun theSessionIsSummarizedFromItsChaptersSummaries() {
        val chs = chapters(session) { null }
        val prompt = SummaryPrompts.session(session, chs, mapOf(chs[0].key to "An intro.", chs[1].key to "Quantization explained.", chs[2].key to "Calibration."))
        assertTrue(prompt.contains("Part 1 (00:00): An intro."))
        assertTrue(prompt.contains("Part 2 (00:41, slide \"Quantization 101\"): Quantization explained."))
        assertTrue(prompt.contains("Notes:\n- Too big: memory, battery\n- Look up calibration"))
        assertTrue(prompt.trimEnd().endsWith("empty if none)."))
        // A session that's one chapter goes straight from what was said.
        val one = session.copy(notes = emptyList())
        val single = SummaryPrompts.session(one, chapters(one) { null }, emptyMap())
        assertTrue(single.contains("What was said:\nThanks for coming."))
    }

    @Test
    fun readsTheReplyGemmaGivesAndCopesWithOthers() {
        // As Gemma 4 E2B actually replied, JSON in a code fence.
        val reply = "```json\n{\n  \"title\": \"Scaling Inference on Edge Devices\",\n  \"summary\": \"Dana Ruiz introduced running real models on phones.\",\n" +
            "  \"keyPoints\": [\"Quantization shrinks models 4-8x.\", \"Phones throttle.\"],\n  \"actionItems\": [\"Look up per-channel calibration.\"]\n}\n```"
        val parsed = SummaryPrompts.parseSession(reply)!!
        assertEquals("Scaling Inference on Edge Devices", parsed.title)
        assertEquals("Dana Ruiz introduced running real models on phones.", parsed.summary)
        assertEquals(listOf("Quantization shrinks models 4-8x.", "Phones throttle."), parsed.keyPoints)
        assertEquals(listOf("Look up per-channel calibration."), parsed.actionItems)
        // "None" isn't an action item.
        assertEquals(emptyList<String>(), SummaryPrompts.parseSession("{\"summary\": \"x y z w v u\", \"actionItems\": [\"None\"]}")!!.actionItems)
        // Plain prose instead of JSON is kept as the summary; nothing usable, nothing.
        assertEquals("The talk covered quantization.", SummaryPrompts.parseSession("The talk covered quantization.")!!.summary)
        assertNull(SummaryPrompts.parseSession("{ }"))
        assertEquals("Quantization stores weights in 8 bits.", SummaryPrompts.cleanChapter("Summary: **Quantization** stores weights\nin 8 bits."))
        assertNull(SummaryPrompts.cleanChapter("\t\t\n"))
    }
}
