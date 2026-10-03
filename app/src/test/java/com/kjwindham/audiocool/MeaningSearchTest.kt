package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.MeaningIndex
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
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Search by meaning: sessions indexed in the background, and found by what was meant; the model is a stand-in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeaningSearchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Meaning by concept: being sleepy, caffeine, memory, a little of everything else. */
    private object Concepts {
        private val concepts = listOf(
            setOf("sleepy", "drowsy", "tired", "adenosine", "awake", "sleep", "pressure"),
            setOf("coffee", "caffeine", "espresso", "cup", "afternoon"),
            setOf("memory", "remember", "learn", "learned", "hippocampus", "replays"),
        )

        fun of(text: String): FloatArray {
            val words = Regex("[a-z]+").findAll(text.lowercase()).map { it.value }.toList()
            val v = FloatArray(concepts.size + 1) { i -> if (i < concepts.size) words.count { it in concepts[i] }.toFloat() else 0.3f }
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(v.size) { v[it] / n }
        }
    }

    private inner class FakeModel : Summarizer {
        override val where = "CPU (4 threads)"
        override val lastSpeed = 300.0 to 12.0
        override fun reply(prompt: String, maxTokens: Int) = "A summary."
        override fun embed(modelPath: String, texts: List<String>) = texts.map { Concepts.of(it) }
        override fun close() {}
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).speechModelOfferDismissed = true
        SummaryController.useForTest { FakeModel() }
        SummaryController.meaningForTest = true
    }

    @After
    fun tearDown() {
        SummaryController.meaningForTest = false
        SummaryController.useForTest(null)
    }

    @Test
    fun aSessionsPassagesAreIndexedOnceAndKept() {
        val id = lecture()
        letItIndex()
        val session = SessionRepository.get(id)!!
        assertEquals(setOf(HitKind.SPEECH, HitKind.NOTE), MeaningIndex.passages(session).map { it.kind }.toSet())
        assertTrue(MeaningIndex.stale(session).isEmpty())
        // Found by meaning, the unrelated left out.
        val found = MeaningIndex.search(listOf(session), Concepts.of("feeling drowsy"))
        assertTrue(found.first().passage.text.startsWith("Adenosine builds up"))
        assertTrue(found.none { it.passage.text.contains("espresso") })
        // A changed transcript: only that passage is redone.
        SessionRepository.setTranscript(id, "r1", listOf(TranscriptSegment(60_000, 66_000, "Adenosine builds up the whole time you're awake.")), "test")
        assertEquals(1, MeaningIndex.stale(SessionRepository.get(id)!!).size)
    }

    @Test
    fun aSearchShowsWhatsCloseInMeaningUnderTheWordMatches() {
        lecture()
        letItIndex()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and what was said").performTextInput("feeling drowsy")
        // After typing pauses, the query's meaning comes from the model.
        compose.mainClock.advanceTimeBy(700)
        letItIndex()
        compose.onNodeWithText("✦ By meaning").assertIsDisplayed()
        compose.onNodeWithText("Adenosine builds up while you're awake, which is why you feel sleepy at night.", substring = true).assertIsDisplayed()
    }

    private fun lecture(): String {
        val session = SessionRepository.create("The Science of Sleep")
        SessionRepository.addRecording(
            session.id,
            Recording(
                "r1", "a.m4a", 1_000_000, 600_000,
                transcript = listOf(
                    TranscriptSegment(60_000, 66_000, "Adenosine builds up while you're awake, which is why you feel sleepy at night."),
                    TranscriptSegment(300_000, 306_000, "A double espresso in the afternoon is still in you at bedtime."),
                ),
                transcriptModel = "test",
            ),
        )
        SessionRepository.addNote(session.id, Note("n1", "The hippocampus replays what you learned", 1_400_000, "r1", 400_000))
        compose.waitForIdle()
        return session.id
    }

    /** Lets the controller notice (it waits for changes to settle) and work through everything. */
    private fun letItIndex() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val done = CountDownLatch(1)
            SummaryController.afterQueued { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }
}
