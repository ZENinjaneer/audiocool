package com.kjwindham.audiocool

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.ChapterSummary
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.data.SpeakerTurn
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.Voice
import com.kjwindham.audiocool.share.WebPage
import com.kjwindham.audiocool.summarize.chapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** A session as a web page: everything in it, in one file, the audio included. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebPageTest {
    @Test
    fun thePageHasTheSummaryChaptersSlidesSpeakersWordsAndAudio() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val s = SessionRepository.create("The Science of Sleep <Lecture 7>")
        SessionRepository.addRecording(
            s.id,
            Recording(
                "r1", "a.m4a", 1_000_000, 600_000,
                transcript = listOf(
                    TranscriptSegment(1_000, 4_000, "Sleep comes in cycles", listOf(0, 400, 900, 1_300)),
                    TranscriptSegment(200_000, 204_000, "Do we still need the email field?", listOf(0, 300, 600, 900, 1_200, 1_500, 1_800)),
                ),
                transcriptModel = "parakeet-unified-en-0.6b",
            ),
        )
        SessionRepository.setSpeakers(s.id, "r1", listOf(SpeakerTurn(0, 100_000, 0), SpeakerTurn(100_000, 600_000, 1)), listOf(Voice(0, "Priya"), Voice(1)))
        val photo = File(SessionRepository.sessionDir(s.id).apply { mkdirs() }, "photo-p1.jpg")
        photo.outputStream().use { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        SessionRepository.addNote(s.id, Note("p1", "", 1_100_000, "r1", 100_000, photo = "photo-p1.jpg", photoText = "Your circadian clock"))
        SessionRepository.addNote(s.id, Note("n0", "Cycles: about 90 minutes", 1_003_000, "r1", 3_000))
        SessionRepository.addNote(s.id, Note("n1", "Look up the maze study", 1_150_000, "r1", 150_000, spoken = true))
        SessionRepository.addNote(s.id, Note("m1", "★ Marked", 1_160_000, "r1", 160_000))
        SessionRepository.setSessionSummary(s.id, SessionSummary("Why we sleep.", listOf("Cycles run 90 minutes."), listOf("No caffeine after noon."), null, "", "test", 0))
        val session = SessionRepository.get(s.id)!!
        val keys = chapters(session) { null }.map { it.key }.toSet()
        keys.forEachIndexed { i, key -> SessionRepository.setChapterSummary(s.id, ChapterSummary(key, "Part $i in short.", "b$i", "test", "Topic $i"), keys) }
        val audio = File(app.cacheDir, "small.m4a").apply { writeBytes("pretend AAC".toByteArray()) }

        val out = ByteArrayOutputStream()
        WebPage.write(out, SessionRepository.get(s.id)!!, { audio }, { WebPage.photo(SessionRepository.photoFile(s.id, it.photo!!)) })
        val html = out.toString("UTF-8")

        File("build/screenshots").mkdirs()
        File("build/screenshots/40-web-page.html").writeText(html)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        // Escaped.
        assertTrue(html.contains("<h1>The Science of Sleep &lt;Lecture 7&gt;</h1>"))
        assertFalse(html.contains("<Lecture 7>"))
        assertTrue(html.contains("10 minutes · 1 slide · 2 notes · 2 speakers"))
        assertTrue(html.contains("<h2>✦ Summary</h2><p>Why we sleep.</p>"))
        assertTrue(html.contains("<li>No caffeine after noon.</li>"))
        assertTrue(html.contains("<nav class=\"chapters\">"))
        assertTrue(html.contains("Topic 0</a></li>"))
        assertTrue(html.contains("<b>Your circadian clock</b></figcaption><img alt=\"Your circadian clock\" src=\"data:image/jpeg;base64,"))
        // Who said it, and each word with its time.
        assertTrue(html.contains(">Priya</span>"))
        assertTrue(html.contains(">Speaker 2</span>"))
        assertTrue(html.contains("<span data-w=\"2300\">cycles</span>"))
        assertTrue(html.contains("Spoken note · 02:30</a>Look up the maze study"))
        assertTrue(html.contains("★ Marked · 02:40"))
        // The audio, whole, in the page.
        val encoded = Regex("""<script type="application/octet-stream" id="audio-0" data-mime="audio/mp4">([^<]*)</script>""").find(html)!!.groupValues[1]
        assertEquals("pretend AAC", String(Base64.getDecoder().decode(encoded)))
        assertTrue(html.trimEnd().endsWith("</html>"))
        // In timeline order: the first thing said, the slide, then the question.
        val first = html.indexOf("<p class=\"said\" data-rec=\"0\" data-ms=\"1000\"")
        val slide = html.indexOf("<b>Your circadian clock</b>")
        val question = html.indexOf("<p class=\"said\" data-rec=\"0\" data-ms=\"200000\"")
        assertTrue("$first < $slide < $question", first in 0 until slide && slide < question)
    }
}
