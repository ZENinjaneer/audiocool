package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.MARK_TEXT
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.paragraphs
import com.kjwindham.audiocool.data.playingSpeechKey
import com.kjwindham.audiocool.data.speechKey
import com.kjwindham.audiocool.data.timelineRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private fun seg(fromSec: Double, toSec: Double, text: String) = TranscriptSegment((fromSec * 1000).toLong(), (toSec * 1000).toLong(), text)

    // A talk: a pause at 30 s, a photo at 41 s, and phrases that run long after that.
    private val talk = listOf(
        seg(0.5, 8.0, "Thanks for coming."),
        seg(8.5, 17.0, "Today: models on phones."),
        seg(17.5, 27.0, "They used to be too big."),
        seg(30.0, 38.0, "What changed?"),
        seg(38.5, 44.0, "Quantization."),
        seg(44.5, 52.0, "Eight bits per weight."),
        seg(52.5, 60.0, "Smaller is faster."),
        seg(60.5, 69.0, "Then calibration."),
        seg(69.5, 75.0, ""),
    )
    private val rec = Recording("r1", "recording-1.m4a", createdAt = 1_000_000, durationMs = 80_000, transcript = talk)

    private fun s(n: Long) = n * 1000

    private fun session(vararg notes: Note, recordings: List<Recording> = listOf(rec)) =
        Session("s", "Talk", createdAt = 0, updatedAt = 0, recordings = recordings, notes = notes.toList())

    private fun describe(rows: List<TimelineRow>) = rows.map { row ->
        when (row) {
            is TimelineRow.RecordingStart -> "rec ${row.number}"
            is TimelineRow.Speech -> (if (row.context) "context " else "said ") + "${row.startMs / 1000}-${row.endMs / 1000}" +
                (if (row.noteIds.isEmpty()) "" else " " + row.noteIds)
            is TimelineRow.Photo -> "photo ${row.number} ${row.note.id}"
            is TimelineRow.Written -> (if (row.note.spoken) "spoken " else "note ") + row.note.id
            is TimelineRow.Mark -> "mark ${row.note.id}"
            is TimelineRow.Fold -> "fold ${row.fromMs / 1000} +${row.skippedMs / 1000}s"
        }
    }

    @Test
    fun phrasesRunTogetherUntilAPauseAPhotoOrAbout25Seconds() {
        val paras = paragraphs(rec, breaks = listOf(s(41)))
        // 0-17 then 17-27 (one more phrase would pass 25 s); 30-44 after a 3 s pause; 44-69 after the
        // photo, which was taken during "Quantization." and so starts the paragraph after that phrase.
        assertEquals(listOf(0L to 17L, 17L to 27L, 30L to 44L, 44L to 69L), paras.map { it.startMs / 1000 to it.endMs / 1000 })
        assertEquals("Thanks for coming. Today: models on phones.", paras[0].text)
        assertEquals("Eight bits per weight. Smaller is faster. Then calibration.", paras[3].text)
    }

    @Test
    fun everythingInOrderWithNotesAfterTheirParagraphAndPhotosAsChapters() {
        val rows = timelineRows(
            session(
                Note("n1", "Too big: memory, battery", 5, "r1", s(20)),
                Note("m1", MARK_TEXT, 6, "r1", s(33)),
                Note("p1", "", 7, "r1", s(41), photo = "photo-p1.jpg"),
                Note("n2", "Look up calibration", 8, "r1", s(42), spoken = true),
                Note("p2", "", 9, "r1", s(64), photo = "photo-p2.jpg"),
                Note("u1", "Written before it started", createdAt = 999_000),
                Note("u2", "Written after", createdAt = 1_100_000),
            ),
            TimelineMode.EVERYTHING,
        )
        assertEquals(
            listOf(
                "note u1",
                "said 0-17", "said 17-27 [n1]", "note n1",
                "said 30-44 [m1]", "mark m1",
                // n2 came after the photo, so it isn't put back with the paragraph before it.
                "photo 1 p1", "spoken n2", "said 44-69",
                "photo 2 p2",
                "note u2",
            ),
            describe(rows),
        )
        assertEquals(speechKey("r1", 17_500), rows[2].key)
    }

    @Test
    fun notesWithContextFoldTheRestAndNotesOnlyLeavesItOut() {
        val s = session(
            Note("n1", "Too big", 5, "r1", s(20)),
            Note("p1", "", 7, "r1", s(41), photo = "photo-p1.jpg"),
            Note("m1", MARK_TEXT, 8, "r1", s(66)),
        )
        assertEquals(
            listOf("fold 0 +16s", "context 17-27 [n1]", "note n1", "fold 30 +14s", "photo 1 p1", "context 44-69 [m1]", "mark m1"),
            describe(timelineRows(s, TimelineMode.CONTEXT)),
        )
        val fold = timelineRows(s, TimelineMode.CONTEXT).first() as TimelineRow.Fold
        assertEquals(speechKey("r1", 500), fold.firstKey)
        assertEquals(listOf("note n1", "photo 1 p1", "mark m1"), describe(timelineRows(s, TimelineMode.NOTES)))
    }

    @Test
    fun aNoteWithoutAParagraphEndsTheFoldBeforeIt() {
        // Written in a 3 s pause after a paragraph, with a photo in between: it stands alone.
        val rows = timelineRows(
            session(Note("p1", "", 1, "r1", s(28), photo = "photo-p1.jpg"), Note("n1", "In the pause", 2, "r1", s(29))),
            TimelineMode.CONTEXT,
        )
        assertEquals(listOf("fold 0 +26s", "photo 1 p1", "note n1", "fold 30 +38s"), describe(rows))
    }

    @Test
    fun severalRecordingsEachGetAHeadingAndFoldsStopAtThem() {
        val second = Recording("r2", "recording-2.m4a", createdAt = 2_000_000, durationMs = 10_000, transcript = listOf(seg(1.0, 5.0, "Questions?")))
        val s = session(Note("n2", "Ask about thermals", 3, "r2", s(3)), recordings = listOf(rec, second))
        assertEquals(
            listOf("rec 1", "fold 0 +64s", "rec 2", "context 1-5 [n2]", "note n2"),
            describe(timelineRows(s, TimelineMode.CONTEXT)),
        )
    }

    @Test
    fun theParagraphPlayingIsTheLastOneStarted() {
        val rows = timelineRows(session(), TimelineMode.EVERYTHING)
        assertNull(playingSpeechKey(rows, "r1", 0))
        // A tap starts playback a moment before a paragraph; it counts as playing from then.
        assertEquals(speechKey("r1", 500), playingSpeechKey(rows, "r1", 200))
        assertEquals(speechKey("r1", 17_500), playingSpeechKey(rows, "r1", s(29)))
        assertNull(playingSpeechKey(rows, "r2", s(29)))
    }

    @Test
    fun spokenNotesAndMarksAreKeptAsSuch() {
        val s = session(Note("n2", "Look up calibration", 8, "r1", s(42), spoken = true), Note("m1", MARK_TEXT, 6, "r1", s(33)))
        val back = SessionJson.decode(SessionJson.encode(s))
        assertTrue(back.notes.single { it.id == "n2" }.spoken)
        assertTrue(back.notes.single { it.id == "m1" }.isMark)
        // Edit a mark's text and it's an ordinary note.
        assertEquals(false, back.notes.single { it.id == "m1" }.copy(text = "Important").isMark)
    }
}
