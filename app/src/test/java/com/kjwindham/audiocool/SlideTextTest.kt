package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ocr.SlideText
import com.kjwindham.audiocool.ocr.TextLine
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.searchAll
import com.kjwindham.audiocool.util.defaultSessionTitle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlideTextTest {
    /** What the fake reader "sees" in each photo file, by name. */
    private val slides = HashMap<String, List<TextLine>>()

    init {
        SlideText.readerForTest = { file -> slides[file.name] ?: throw IOException("unreadable") }
    }

    @After
    fun tearDown() {
        SlideText.readerForTest = null
    }

    private fun waitForReading() {
        val done = CountDownLatch(1)
        SlideText.afterQueued { done.countDown() }
        done.await(10, TimeUnit.SECONDS)
    }

    private fun addPhoto(sessionId: String, noteId: String, at: Long) {
        File(SessionRepository.sessionDir(sessionId).apply { mkdirs() }, "photo-$noteId.jpg").writeBytes(ByteArray(10))
        SessionRepository.addNote(sessionId, Note(noteId, "", at, "r1", at, photo = "photo-$noteId.jpg"))
        SlideText.photoAdded(sessionId, noteId)
    }

    private fun titleSlide() = listOf(
        TextLine("Scaling Inference on the Edge", 200, 300, 1700, 390, 90, 0.95f),
        TextLine("Dana Ruiz · Platform Team", 300, 480, 1000, 520, 40, 0.95f),
    )

    @Test
    fun aSessionNamedAfterItsStartTimeTakesTheTitleOffItsFirstSlide() {
        val created = System.currentTimeMillis()
        val session = SessionRepository.create(defaultSessionTitle(created))
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", session.createdAt, 0))
        // The first photo has no title (a blurry shot); the second does; the third doesn't change it again.
        slides["photo-p1.jpg"] = emptyList()
        slides["photo-p2.jpg"] = titleSlide()
        slides["photo-p3.jpg"] = listOf(TextLine("Results and Next Steps", 100, 100, 900, 180, 80, 0.95f))
        addPhoto(session.id, "p1", 1_000)
        addPhoto(session.id, "p2", 2_000)
        addPhoto(session.id, "p3", 3_000)
        waitForReading()

        val after = SessionRepository.get(session.id)!!
        assertEquals("Scaling Inference on the Edge", after.title)
        assertEquals("", after.notes.first { it.id == "p1" }.photoText)
        assertEquals("Scaling Inference on the Edge\nDana Ruiz · Platform Team", after.notes.first { it.id == "p2" }.photoText)

        // What's on the slides can be searched; a hit plays from when the photo was taken.
        val hit = searchAll(SessionRepository.sessions.value, "platform team").single()
        assertEquals(HitKind.PHOTO, hit.kind)
        assertEquals("p2", hit.noteId)
        assertEquals(2_000L, hit.atMs)
        assertEquals("Dana Ruiz · Platform Team", hit.text)
    }

    @Test
    fun aNameYouGaveIsKeptAndUnreadablePhotosAreRetriedLater() {
        val session = SessionRepository.create("Bio 101")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", session.createdAt, 0))
        slides["photo-p1.jpg"] = titleSlide()
        addPhoto(session.id, "p1", 1_000)
        addPhoto(session.id, "p2", 2_000) // the reader fails on this one
        waitForReading()

        val after = SessionRepository.get(session.id)!!
        assertEquals("Bio 101", after.title)
        assertEquals("Scaling Inference on the Edge\nDana Ruiz · Platform Team", after.notes.first { it.id == "p1" }.photoText)
        assertNull(after.notes.first { it.id == "p2" }.photoText)
    }
}
