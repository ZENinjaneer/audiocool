package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.desktop.DesktopSummaries
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.summarize.Summarizer
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Summaries written on the paired desktop (AudioCool Desktop's bigger model), standing in for it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesktopSummariesTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val studio = DesktopSummaries.Model("gemma-4-26b-a4b", "Gemma 4 26B", "Studio PC")
    private val asked = mutableListOf<Pair<String, Int>>()

    /** Replies as the desktop's model would, saying so. */
    private fun desktopReply(prompt: String, maxTokens: Int): String {
        synchronized(asked) { asked += prompt to maxTokens }
        return when {
            prompt.startsWith("Give this part") -> "Title: From the desktop\nSummary: The desktop's summary of this part."
            prompt.contains("[1]") -> "Adenosine builds up while you're awake [1]."
            else -> "```json\n{\"title\": \"Sleep, Explained\", \"summary\": \"Written on the desktop.\", \"keyPoints\": [\"Adenosine builds up.\"], \"actionItems\": []}\n```"
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).summaries = true
        Prefs(app).speechModelOfferDismissed = true
    }

    @After
    fun tearDown() {
        DesktopSummaries.useForTest(null, null)
        SummaryController.useForTest(null)
        SummaryController.clearAnswer()
    }

    @Test
    fun withoutThePhonesModelTheDesktopWritesTheSummaries() {
        DesktopSummaries.useForTest(studio, ::desktopReply)
        val id = talk()
        letItWork()
        val s = SessionRepository.get(id)!!
        assertEquals("Written on the desktop.", s.summary!!.text)
        assertEquals("gemma-4-26b-a4b@desktop", s.summary!!.model)
        assertTrue(asked.isNotEmpty())
        compose.onNodeWithText(s.title).performClick()
        compose.onNodeWithText("Made on your desktop").assertIsDisplayed()
    }

    @Test
    fun whatThePhoneWroteIsWrittenAgainOnTheDesktop() {
        // First the phone's own model (a stand-in), away from the desktop.
        SummaryController.useForTest {
            object : Summarizer {
                override val where = "CPU (4 threads)"
                override val lastSpeed = 300.0 to 12.0
                override fun reply(prompt: String, maxTokens: Int) =
                    "```json\n{\"title\": \"Sleep\", \"summary\": \"Written on the phone.\", \"keyPoints\": [], \"actionItems\": []}\n```"
                override fun close() {}
            }
        }
        val id = talk()
        letItWork()
        assertEquals("Written on the phone.", SessionRepository.get(id)!!.summary!!.text)
        assertEquals(SummaryController.MODEL, SessionRepository.get(id)!!.summary!!.model)
        // Then within reach of the desktop: its bigger model writes it again.
        SummaryController.useForTest(null)
        DesktopSummaries.useForTest(studio, ::desktopReply)
        letItWork()
        assertEquals("Written on the desktop.", SessionRepository.get(id)!!.summary!!.text)
        // And only once.
        val count = asked.size
        SummaryController.schedule()
        letItWork()
        assertEquals(count, asked.size)
    }

    @Test
    fun questionsAreAnsweredOnTheDesktopAndItsAbsenceIsNoticed() {
        DesktopSummaries.useForTest(studio, ::desktopReply)
        val id = talk()
        letItWork()
        assertTrue(SummaryController.canAsk)
        SummaryController.ask(id, "Why does adenosine make us sleepy?")
        letItWork()
        assertEquals("Adenosine builds up while you're awake.", SummaryController.answer.value!!.text)
        // The desktop stops answering: the question says so, and the phone stops sending it work.
        DesktopSummaries.useForTest(studio) { _, _ -> throw IOException("Network is unreachable") }
        SummaryController.ask(id, "What does caffeine do?")
        letItWork()
        assertEquals("Couldn't answer that just now.", SummaryController.answer.value!!.error)
        assertNull(DesktopSummaries.model)
        assertTrue(!SummaryController.canAsk)
    }

    @Test
    fun theSummariesDialogOffersTheDesktop() {
        Prefs(app).desktopPairing = "http://192.168.1.20:8765\nT0KEN\nStudio PC"
        DesktopSync.init(app)
        DesktopSummaries.useForTest(studio, ::desktopReply)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Summaries").performClick()
        compose.onNodeWithText("Summarize recordings").assertIsDisplayed()
        compose.onNodeWithText("Written on Studio PC by Gemma 4 26B", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download to the phone").assertIsDisplayed()
        // A dialog is a window of its own (captureToImage can't draw it here).
        val window = ShadowDialog.getLatestDialog().window!!.decorView
        save(Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also { window.draw(Canvas(it)) }, "70-summaries-desktop")
        DesktopSync.unpair()
    }

    private fun talk(): String {
        val s = SessionRepository.create("The Science of Sleep")
        val rec = Recording("r1", "a.m4a", 1_000_000, 300_000, transcript = listOf(
            TranscriptSegment(1_000, 9_000, "Adenosine builds up in your brain the whole time you're awake, and the longer you're up, the stronger the pressure to sleep."),
            TranscriptSegment(20_000, 28_000, "Caffeine works by blocking adenosine receptors; it hides the pressure for a while."),
        ), transcriptModel = "parakeet-unified-en-0.6b")
        SessionRepository.addRecording(s.id, rec)
        compose.waitForIdle()
        return s.id
    }

    /** Lets the controller notice (it waits for changes to settle) and work through everything. */
    private fun letItWork() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val done = CountDownLatch(1)
            SummaryController.afterQueued { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
