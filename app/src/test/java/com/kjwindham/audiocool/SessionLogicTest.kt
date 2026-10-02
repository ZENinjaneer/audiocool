package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.NoteFocus
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.currentNoteId
import com.kjwindham.audiocool.data.highlightedNoteId
import com.kjwindham.audiocool.data.playbackStartFor
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.isEnterKeystroke
import com.kjwindham.audiocool.util.noteLabel
import com.kjwindham.audiocool.util.removeEnter
import com.kjwindham.audiocool.util.sessionMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogicTest {
    private val rec1 = Recording("r1", "recording-1.aac", createdAt = 1_000_000, durationMs = 600_000)
    private val rec2 = Recording("r2", "recording-2.aac", createdAt = 2_000_000, durationMs = 300_000)

    private fun session(recordings: List<Recording>, notes: List<Note>) =
        Session("s", "Bio 101", createdAt = 0, updatedAt = 0, recordings = recordings, notes = notes)

    @Test
    fun photosAreNotesAndTheFirstOneIsTheThumbnailUntilAnotherIsPicked() {
        val late = Note("p2", "", createdAt = 5_000_000, "r1", 400_000, photo = "photo-p2.jpg")
        val early = Note("p1", "Title slide", createdAt = 5_000_001, "r1", 30_000, photo = "photo-p1.jpg")
        val s = session(listOf(rec1), listOf(late, Note("n1", "A thought", 1_100_000, "r1", 100_000), early))
        assertEquals("p1", s.thumbnailNote()?.id)
        assertEquals("p2", s.copy(thumbnail = "p2").thumbnailNote()?.id)
        // A thumbnail that isn't a photo (any more) falls back to the first photo.
        assertEquals("p1", s.copy(thumbnail = "n1").thumbnailNote()?.id)
        assertNull(session(listOf(rec1), listOf(Note("n1", "A thought", 1_100_000))).thumbnailNote())

        val back = SessionJson.decode(SessionJson.encode(s.copy(thumbnail = "p2")))
        assertEquals("p2", back.thumbnail)
        assertEquals(s.notes, back.notes)
        assertEquals(listOf("photo-p2.jpg", "photo-p1.jpg"), back.photoFiles())
        assertTrue(sessionMarkdown(s).contains("- [00:30] ![Title slide](photo-p1.jpg)"))
        assertTrue(sessionMarkdown(s).contains("- [06:40] ![Photo](photo-p2.jpg)"))
    }

    @Test
    fun theTextInAPhotoIsPartOfTheNotes() {
        val slide = Note("p1", "", 5_000_000, "r1", 30_000, photo = "photo-p1.jpg", photoText = "Why on-device?\nLatency under 50 ms\nWorks offline")
        val scenery = Note("p2", "", 5_000_001, "r1", 60_000, photo = "photo-p2.jpg", photoText = "Il\n1")
        val unread = Note("p3", "", 5_000_002, "r1", 90_000, photo = "photo-p3.jpg")
        assertEquals("Why on-device?\nLatency under 50 ms\nWorks offline", slide.textInPhoto)
        // A few stray marks read off a picture with no words in it aren't worth showing.
        assertNull(scenery.textInPhoto)
        assertNull(unread.textInPhoto)
        assertEquals(
            "- [00:30] ![Photo](photo-p1.jpg)\n" +
                "  > Why on-device?\n  > Latency under 50 ms\n  > Works offline\n" +
                "- [01:00] ![Photo](photo-p2.jpg)\n" +
                "- [01:30] ![Photo](photo-p3.jpg)\n",
            sessionMarkdown(session(listOf(rec1), listOf(slide, scenery, unread))).substringAfter("\n\n").substringBefore("\nAudio:"),
        )
    }

    @Test
    fun formatsTimes() {
        assertEquals("00:00", formatTime(0))
        assertEquals("00:00", formatTime(-5))
        assertEquals("00:59", formatTime(59_999))
        assertEquals("01:05", formatTime(65_000))
        assertEquals("59:59", formatTime(3_599_000))
        assertEquals("1:02:05", formatTime(3_725_000))
    }

    @Test
    fun notesAddedDuringReplaySortIntoTheTimeline() {
        val live1 = Note("a", "intro", createdAt = 1_010_000, recId = "r1", offsetMs = 10_000)
        val live2 = Note("b", "main point", createdAt = 1_300_000, recId = "r1", offsetMs = 300_000)
        val onReplay = Note("c", "added on replay", createdAt = 9_000_000, recId = "r1", offsetMs = 120_000)
        val beforeRecording = Note("d", "agenda", createdAt = 900_000)
        val secondTake = Note("e", "second take", createdAt = 2_050_000, recId = "r2", offsetMs = 50_000)
        val s = session(listOf(rec1, rec2), listOf(live1, live2, onReplay, beforeRecording, secondTake))
        assertEquals(listOf("d", "a", "c", "b", "e"), s.orderedNotes().map { it.id })
    }

    @Test
    fun currentNoteIsTheLatestOneAtOrBeforeThePosition() {
        val notes = listOf(
            Note("a", "", 0, "r1", 10_000),
            Note("b", "", 0, "r1", 60_000),
            Note("c", "", 0, "r2", 5_000),
            Note("d", "", 0),
        )
        assertNull(currentNoteId(notes, "r1", 9_999))
        assertEquals("a", currentNoteId(notes, "r1", 10_000))
        assertEquals("a", currentNoteId(notes, "r1", 59_999))
        assertEquals("b", currentNoteId(notes, "r1", 61_000))
        assertEquals("c", currentNoteId(notes, "r2", 6_000))
        assertNull(currentNoteId(notes, null, 6_000))
    }

    @Test
    fun playbackStartsBeforeTheNoteButNotBeforeThePreviousOne() {
        val a = Note("a", "", 0, "r1", 10_000)
        val b = Note("b", "", 0, "r1", 12_000)
        val c = Note("c", "", 0, "r1", 30_000)
        val sameTimeAsC = Note("d", "", 0, "r1", 30_000)
        val otherRecording = Note("e", "", 0, "r2", 29_000)
        val early = Note("f", "", 0, "r2", 1_000)
        val notes = listOf(a, b, c, sameTimeAsC, otherRecording, early)
        assertEquals(7_000L, playbackStartFor(a, notes, 3_000))
        assertEquals(10_000L, playbackStartFor(b, notes, 3_000)) // clamped to a
        assertEquals(27_000L, playbackStartFor(c, notes, 3_000)) // r2's note at 29 s doesn't count
        assertEquals(27_000L, playbackStartFor(sameTimeAsC, notes, 3_000)) // a note at the same time isn't "previous"
        assertEquals(0L, playbackStartFor(early, notes, 3_000))
        assertEquals(30_000L, playbackStartFor(c, notes, 0))
    }

    @Test
    fun aTappedNoteStaysHighlightedThroughItsLeadIn() {
        val notes = listOf(Note("a", "", 0, "r1", 10_000), Note("b", "", 0, "r1", 30_000))
        val focus = NoteFocus("b", "r1", fromMs = 27_000, untilMs = 30_000)
        assertEquals("b", highlightedNoteId(notes, "r1", 27_000, focus))
        assertEquals("b", highlightedNoteId(notes, "r1", 26_800, focus)) // seek landed a frame short
        assertEquals("b", highlightedNoteId(notes, "r1", 29_999, focus))
        assertEquals("b", highlightedNoteId(notes, "r1", 31_000, focus)) // reached: normal rule
        assertEquals("a", highlightedNoteId(notes, "r1", 20_000, focus)) // user sought elsewhere
        assertEquals("a", highlightedNoteId(notes, "r1", 28_000, null))
        assertEquals("a", highlightedNoteId(notes, "r1", 28_000, focus.copy(recId = "r2")))
        assertEquals("a", highlightedNoteId(notes, "r1", 28_000, focus.copy(noteId = "deleted")))
    }

    @Test
    fun labelsShowTheRecordingNumberOnlyWhenThereAreSeveral() {
        val n = Note("a", "x", 0, "r2", 75_000)
        assertEquals("01:15", noteLabel(session(listOf(rec2), listOf(n)), n))
        assertEquals("#2 01:15", noteLabel(session(listOf(rec1, rec2), listOf(n)), n))
        assertNull(noteLabel(session(listOf(rec1), listOf(n)), n)) // its recording is gone
        assertNull(noteLabel(session(listOf(rec1), emptyList()), Note("b", "x", 0)))
    }

    @Test
    fun enterKeyIsDetectedButPastedTextIsNot() {
        assertTrue(isEnterKeystroke("hello", "hello\n"))
        assertTrue(isEnterKeystroke("hello", "hel\nlo"))
        assertTrue(isEnterKeystroke("teh", "the\n")) // autocorrect committed together with Enter
        assertFalse(isEnterKeystroke("hello", "hello!"))
        assertFalse(isEnterKeystroke("hello", "hello\nworld"))
        assertFalse(isEnterKeystroke("a", "a\nb\n"))
        assertFalse(isEnterKeystroke("hello\n", "hello"))

        assertEquals("hello", removeEnter("hello", "hello\n"))
        assertEquals("the", removeEnter("teh", "the\n"))
        assertEquals("hello", removeEnter("hello", "hel\nlo"))
        assertEquals("line one\nline two", removeEnter("line one\nline two", "line one\nline two\n"))
    }

    @Test
    fun markdownCanIncludeTheTranscript() {
        val s = session(
            listOf(rec1.copy(transcript = listOf(TranscriptSegment(65_000, 70_000, "The Krebs cycle.")))),
            listOf(Note("a", "intro", 1_010_000, "r1", 10_000)),
        )
        assertTrue(sessionMarkdown(s, includeTranscript = true).contains("## Transcript\n\n- [01:05] The Krebs cycle.\n"))
        assertFalse(sessionMarkdown(s).contains("Transcript"))
    }

    @Test
    fun markdownListsNotesWithTimestamps() {
        val s = session(listOf(rec1), listOf(Note("a", "intro", 1_010_000, "r1", 10_000), Note("b", "agenda", 900_000)))
        val md = sessionMarkdown(s)
        assertTrue(md.startsWith("# Bio 101\n"))
        assertTrue(md.contains("- agenda\n- [00:10] intro\n"))
        assertTrue(md.contains("Audio: recording-1.aac (10:00)"))
    }

    @Test
    fun jsonRoundTrips() {
        val transcribed = rec1.copy(transcript = listOf(TranscriptSegment(2_050, 5_830, "Ask not what your country can do for you.")))
        val s = Session(
            "s1", "Bio 101 \"Lecture\" ✓", 1, 2, listOf(transcribed, rec2.copy(transcript = emptyList())),
            listOf(Note("a", "line one\nline two", 5, "r1", 1234), Note("b", "plain", 6)),
        )
        assertEquals(s, SessionJson.decode(SessionJson.encode(s)))
    }
}
