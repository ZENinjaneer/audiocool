package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.summarize.Summarizer
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.summarize.chapterKey
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.util.Prefs
import com.kjwindham.audiocool.util.defaultSessionTitle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Summaries made in the background as a session allows, shown on its timeline. The model is a stand-in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummaryFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val prompts = mutableListOf<String>()

    /** Answers like Gemma: a sentence for a part, JSON in a code fence for the whole session. */
    private inner class FakeGemma : Summarizer {
        override val where = "CPU (4 threads)"
        override val lastSpeed = 300.0 to 12.0

        override fun reply(prompt: String, maxTokens: Int): String {
            synchronized(prompts) { prompts += prompt }
            return if (prompt.startsWith("Summarize this part")) {
                val said = prompt.substringAfter("What was said:\n", "").trim().substringBefore(".")
                "The speaker covers: $said."
            } else {
                "```json\n{\"title\": \"Running Models on Phones\", \"summary\": \"A talk on fitting models onto phones.\", " +
                    "\"keyPoints\": [\"Quantize the weights.\", \"Measure on real phones.\"], \"actionItems\": [\"Look up calibration.\"]}\n```"
            }
        }

        override fun close() {}
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).summaries = true
        SummaryController.useForTest { FakeGemma() }
    }

    @After
    fun tearDown() {
        LiveTranscription.pretendForTest(null, null)
        RecorderController.stop()
        SummaryController.useForTest(null)
    }

    @Test
    fun aSessionIsSummarizedPartByPartThenAsAWhole() {
        val session = SessionRepository.create(defaultSessionTitle()).let {
            // Named after its start time (as a new session is), so a summary's title may replace it.
            SessionRepository.rename(it.id, defaultSessionTitle(it.createdAt))
            SessionRepository.get(it.id)!!
        }
        val rec = Recording("rec1", "recording-1.m4a", 5_000, 200_000, transcript = listOf(
            TranscriptSegment(1_000, 9_000, "Welcome to the talk on phones. Today we look at why running a language model on the phone in your pocket is now possible, and what it takes to make one fit and run fast."),
            TranscriptSegment(60_000, 70_000, "Quantization makes models small."),
            TranscriptSegment(130_000, 140_000, "Always measure on real phones."),
        ), transcriptModel = "parakeet-unified-en-0.6b")
        SessionRepository.addRecording(session.id, rec)
        SessionRepository.setTranscript(session.id, rec.id, rec.transcript!!, "parakeet-unified-en-0.6b")
        slide(session.id, "s1", 50_000, "Quantization 101")
        slide(session.id, "s2", 120_000, "Measure on real devices")
        SessionRepository.addNote(session.id, Note("n1", "Look up calibration", 3, rec.id, 65_000))
        letSummariesRun()

        val done = SessionRepository.get(session.id)!!
        assertEquals(
            listOf(chapterKey(rec.id, 0), chapterKey(rec.id, 50_000), chapterKey(rec.id, 120_000)),
            done.chapterSummaries.map { it.key }.sorted().let { keys -> keys.sortedBy { it.substringAfterLast(':').toLong() } },
        )
        assertEquals("The speaker covers: Quantization makes models small.", done.chapterSummaries.single { it.key == chapterKey(rec.id, 50_000) }.text)
        assertEquals("A talk on fitting models onto phones.", done.summary!!.text)
        assertEquals(listOf("Look up calibration."), done.summary!!.actionItems)
        // Its date-and-time name gives way to the summary's title.
        assertEquals("Running Models on Phones", done.title)
        // The session was summarized from its parts' summaries.
        assertTrue(prompts.last().contains("Part 2 (00:50, slide \"Quantization 101\"): The speaker covers: Quantization makes models small."))

        compose.onNodeWithText("Running Models on Phones").performClick()
        compose.onNodeWithText("✦ Summary").assertIsDisplayed()
        compose.onNodeWithText("A talk on fitting models onto phones.").assertIsDisplayed()
        compose.onNodeWithText("Made on this phone").assertIsDisplayed()
        compose.onNodeWithText("Key points and 1 action item").performClick()
        compose.onNodeWithText("•  Quantize the weights.").assertIsDisplayed()
        compose.onNodeWithText("☐  Look up calibration.").assertIsDisplayed()
        compose.onNodeWithText("Show less").performClick()
        // Each part's summary sits on the timeline where the part starts.
        compose.onNodeWithText("The speaker covers: Welcome to the talk on phones.").assertExists()
        screenshot("20-summary")
        compose.onNodeWithText("Notes only").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("The speaker covers: Quantization makes models small."))
        compose.onNodeWithText("The speaker covers: Quantization makes models small.").assertIsDisplayed()
        screenshot("21-summary-notes-only")

        // Nothing changed, nothing redone.
        val asked = prompts.size
        letSummariesRun()
        assertEquals(asked, prompts.size)
    }

    @Test
    fun whileRecordingEachPartIsSummarizedOnceTheTranscriptPassesTheNextSlide() {
        val session = SessionRepository.create("Live")
        assertTrue(RecorderController.start(session.id))
        val recId = RecorderController.state.value.recId!!
        LiveTranscription.pretendForTest(session.id, recId)
        SessionRepository.appendTranscriptSegment(
            session.id, recId,
            TranscriptSegment(1_000, 9_000, "Intro to the topic. We will cover how small models are made, how they are measured on phones, and what to watch for when you ship one to real people."),
        )
        slide(session.id, "s1", 20_000, "First slide")
        // The transcript hasn't reached the slide: the part before it may still grow.
        letSummariesRun()
        assertTrue(SessionRepository.get(session.id)!!.chapterSummaries.isEmpty())

        // Now it's past the slide, so the part before is complete and summarized, mid-recording.
        SessionRepository.appendTranscriptSegment(session.id, recId, TranscriptSegment(21_000, 28_000, "Now the first slide."))
        letSummariesRun()
        val midway = SessionRepository.get(session.id)!!
        assertEquals(listOf(chapterKey(recId, 0)), midway.chapterSummaries.map { it.key })
        assertEquals("The speaker covers: Intro to the topic.", midway.chapterSummaries.single().text)
        compose.onNodeWithText("Live").performClick()
        // The part so far has its summary on the timeline, above what was said; the whole is still to come.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("The speaker covers: Intro to the topic."))
        compose.onNodeWithText("The speaker covers: Intro to the topic.").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("✦ A summary is on its way."))
        compose.onNodeWithText("✦ A summary is on its way.").assertIsDisplayed()
        screenshot("22-summary-while-recording")
        assertEquals(null, midway.summary)

        // Once it stops, the last part, then the whole.
        LiveTranscription.pretendForTest(null, null)
        RecorderController.stop()
        letSummariesRun()
        val done = SessionRepository.get(session.id)!!
        assertEquals(2, done.chapterSummaries.size)
        assertTrue(done.summary != null)
        // A name you gave is kept.
        assertEquals("Live", done.title)
    }

    @Test
    fun withoutTheModelASessionOffersSummaries() {
        SummaryController.useForTest(null)
        Prefs(app).summaryOfferDismissed = false
        val session = SessionRepository.create("Chem")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", 1_000, 60_000))
        SessionRepository.setTranscript(session.id, "r1", listOf(TranscriptSegment(1_000, 5_000, "Atoms bond.")), "parakeet-unified-en-0.6b")
        compose.waitForIdle()
        compose.onNodeWithText("Chem").performClick()
        compose.onNodeWithText("✦ Summaries").assertIsDisplayed()
        compose.onNodeWithText("Set up").performClick()
        compose.onNodeWithText("Download the summary model?").assertIsDisplayed()
        compose.onNodeWithText("Gemma 4 E2B", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.onAllNodesWithText("✦ Summaries").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertTrue(Prefs(app).summaryOfferDismissed)
    }

    private fun slide(sessionId: String, id: String, atMs: Long, title: String) {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(28, 32, 51)) }
        val file = SessionRepository.photoFile(sessionId, "photo-$id.jpg").apply { parentFile?.mkdirs() }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val recId = SessionRepository.get(sessionId)!!.recordings.last().id
        SessionRepository.addNote(sessionId, Note(id, "", 2, recId, atMs, photo = file.name))
        SessionRepository.setPhotoText(sessionId, id, "$title\nA bullet point")
    }

    /** Lets the controller notice (it waits a moment for changes to settle) and work through everything. */
    private fun letSummariesRun() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val done = CountDownLatch(1)
            SummaryController.afterQueued { done.countDown() }
            done.await(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
