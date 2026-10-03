package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.summarize.AskPrompts
import com.kjwindham.audiocool.summarize.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking about a session: which excerpts go to the model, how it's asked, and reading its answer. */
class AskPromptsTest {
    private val session = Session(
        "s", "The Science of Sleep", 1, 1,
        recordings = listOf(
            Recording(
                "r1", "a.m4a", 0, 3_600_000,
                transcript = listOf(
                    TranscriptSegment(60_000, 66_000, "Sleep comes in cycles of about ninety minutes."),
                    TranscriptSegment(785_000, 792_000, "During deep sleep the hippocampus replays what you learned during the day."),
                    TranscriptSegment(930_000, 937_000, "Students who sleep after studying remember far more the next day."),
                    TranscriptSegment(1_620_000, 1_627_000, "Caffeine has a half-life of about five to six hours."),
                ),
            ),
        ),
        notes = listOf(Note("n1", "All-nighters = worst strategy for exams", 0, "r1", 1_005_000)),
    )

    @Test
    fun aQuestionIsToldFromWordsToFind() {
        assertTrue(AskPrompts.looksLikeQuestion("What does deep sleep do?"))
        assertTrue(AskPrompts.looksLikeQuestion("why do students remember more"))
        assertFalse(AskPrompts.looksLikeQuestion("caffeine"))
        assertFalse(AskPrompts.looksLikeQuestion("deep sleep"))
    }

    @Test
    fun theExcerptsToDoWithTheQuestionGoToTheModelInOrder() {
        val excerpts = AskPrompts.relevant(AskPrompts.passages(session), "How does sleep help students remember what they learned?")
        val texts = excerpts.map { it.text }
        assertTrue(texts.any { it.startsWith("During deep sleep") })
        assertTrue(texts.any { it.startsWith("Students who sleep") })
        assertFalse(texts.any { it.startsWith("Caffeine") })
        // In timeline order, each with its moment.
        assertEquals(excerpts.sortedBy { it.moment.atMs }, excerpts)
        assertEquals("13:05", excerpts.first { it.text.startsWith("During") }.moment.label)

        val prompt = AskPrompts.prompt(session, "How does sleep help students remember?", excerpts)
        assertTrue(prompt.contains("[1] (")
        )
        assertTrue(prompt.contains("Question: How does sleep help students remember?"))
        // Nothing to do with the question: nothing to answer from.
        assertTrue(AskPrompts.relevant(AskPrompts.passages(session), "What about the weather?").isEmpty())
    }

    @Test
    fun theAnswerLosesItsExcerptNumbersAndKeepsTheirMoments() {
        val excerpts = AskPrompts.relevant(AskPrompts.passages(session), "How does sleep help students remember what they learned?")
        val (text, moments) = AskPrompts.parse(
            "Deep sleep replays the day [1]. Students who sleep remember more [2, 3][2]. See also [9].",
            excerpts,
        )
        assertEquals("Deep sleep replays the day. Students who sleep remember more. See also.", text)
        assertEquals(excerpts.take(3).map { it.moment }, moments)
        assertTrue(moments.all { it is Moment })
        // As the model says it when it can't find an answer.
        assertEquals("It isn't in this recording." to emptyList<Moment>(), AskPrompts.parse("it isn't in this recording.", excerpts))
    }
}
