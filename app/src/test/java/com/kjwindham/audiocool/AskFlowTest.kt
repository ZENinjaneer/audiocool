package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.summarize.Summarizer
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.util.Prefs
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Asking a session a question: the answer from the summary model (a stand-in), and going to where it came from. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AskFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var asked: String? = null

    /** Answers from the excerpts it's given, citing them; anything else gets a stock summary. */
    private inner class FakeGemma : Summarizer {
        override val where = "CPU (4 threads)"
        override val lastSpeed = 300.0 to 12.0

        override fun reply(prompt: String, maxTokens: Int): String {
            if (prompt.contains("Question:")) {
                asked = prompt
                // Cites the excerpts it uses by their numbers, as asked.
                fun number(start: String) = Regex("""\[(\d+)] \([^)]*\) $start""").find(prompt)!!.groupValues[1]
                return "During deep sleep the brain replays what you learned [${number("During deep sleep")}]. " +
                    "Students who slept remembered more [${number("Students who sleep")}]."
            }
            return "A summary."
        }

        override fun close() {}
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).summaries = true
        Prefs(app).speechModelOfferDismissed = true
        Prefs(app).timelineMode = "EVERYTHING"
        SummaryController.useForTest { FakeGemma() }
    }

    @After
    fun tearDown() {
        SummaryController.clearAnswer()
        SummaryController.useForTest(null)
        PlayerController.release()
    }

    @Test
    fun aQuestionIsAnsweredFromTheSessionAndAMomentGoesThere() {
        val session = SessionRepository.create("The Science of Sleep")
        val audio = File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(3_600_000, 0))
        SessionRepository.addRecording(
            session.id,
            Recording(
                "r1", audio.name, 1_000_000, 3_600_000,
                transcript = listOf(
                    TranscriptSegment(60_000, 66_000, "Sleep comes in cycles of about ninety minutes."),
                    TranscriptSegment(785_000, 792_000, "During deep sleep the hippocampus replays what you learned during the day."),
                    TranscriptSegment(930_000, 937_000, "Students who sleep after studying remember far more the next day."),
                    TranscriptSegment(1_620_000, 1_627_000, "Caffeine has a half-life of about five to six hours."),
                ),
                transcriptModel = "parakeet-unified-en-0.6b",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("The Science of Sleep").performClick()
        compose.onNodeWithContentDescription("Search this session").performClick()
        compose.onNodeWithText("Search or ask").performTextInput("How does sleep help students remember?")
        compose.onNodeWithText("Ask: “How does sleep help students remember?”").assertIsDisplayed()
        // Enter on a question asks it.
        compose.onNodeWithText("How does sleep help students remember?").performImeAction()
        letTheModelAnswer()

        assertTrue(asked!!.contains("Question: How does sleep help students remember?"))
        compose.onNodeWithText("✦ Answer").assertIsDisplayed()
        compose.onNodeWithText("During deep sleep the brain replays what you learned. Students who slept remembered more.").assertIsDisplayed()
        screenshot("36-ask-this-session")

        // A moment it came from: the timeline, playing from there.
        compose.onNodeWithContentDescription("Play from 15:30").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search or ask").assertDoesNotExist()
        assertEquals(930_000L - 300, PlayerController.state.value.positionMs)
    }

    private fun letTheModelAnswer() {
        shadowOf(Looper.getMainLooper()).idle()
        val done = CountDownLatch(1)
        SummaryController.afterQueued { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
