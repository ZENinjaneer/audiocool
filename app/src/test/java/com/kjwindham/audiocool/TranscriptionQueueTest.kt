package com.kjwindham.audiocool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.transcribe.TranscriptionController.Job
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TranscriptionQueueTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() = TranscriptionController.cancelAll()

    @Test
    fun theQueueSurvivesARestartIncludingWhereToResume() {
        TranscriptionController.enqueue("s1", listOf("r1", "r2"))
        TranscriptionController.enqueue("s1", listOf("r1"), fromMs = 9_000) // already queued: ignored
        TranscriptionController.enqueue("s2", listOf("r9"), fromMs = 5_000)
        val expected = listOf(Job("s1", "r1"), Job("s1", "r2"), Job("s2", "r9", 5_000))
        assertEquals(expected, TranscriptionController.state.value.queue)

        TranscriptionController.init(app) // as if the app had been restarted
        assertEquals(expected, TranscriptionController.state.value.queue)
        assertTrue(TranscriptionController.state.value.isPending("s2", "r9"))
    }

    @Test
    fun liveTranscriptionCutOffByTheAppDyingIsFinishedFromWhereItGotTo() {
        val session = SessionRepository.create("Bio")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.aac", 1_000L, durationMs = 0))
        SessionRepository.appendTranscriptSegment(session.id, "r1", TranscriptSegment(1_000, 4_000, "First phrase."))
        SessionRepository.appendTranscriptSegment(session.id, "r1", TranscriptSegment(5_000, 7_000, "Second phrase."))
        Prefs(app).liveRecording = "${session.id}:r1"

        TranscriptionController.init(app)

        assertEquals(listOf(Job(session.id, "r1", fromMs = 7_000)), TranscriptionController.state.value.queue)
        assertEquals("", Prefs(app).liveRecording)
    }
}
