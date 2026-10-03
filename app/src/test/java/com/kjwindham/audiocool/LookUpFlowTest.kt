package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.lookup.Explain
import com.kjwindham.audiocool.lookup.LookUp
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Looking up a word from what was said, and explaining a slide; the model and Wikipedia are stand-ins. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookUpFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val sleepSaid = "Each cycle moves from light sleep into deep sleep, and then up into REM, where most dreaming happens."

    private inner class FakeGemma : Summarizer {
        override val where = "CPU (4 threads)"
        override val lastSpeed = 300.0 to 12.0

        override fun reply(prompt: String, maxTokens: Int): String = when {
            prompt.contains("What does \"REM\" mean here?") -> "Rapid eye movement sleep, the dreaming stage."
            prompt.contains("What does \"SCN\" mean here?") -> "The SCN is the suprachiasmatic nucleus, the brain's clock."
            prompt.contains("Question: Explain this slide") -> "The SCN keeps the body's time [1], and morning light resets it [2]."
            else -> "A summary."
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
        LookUp.fetchForTest = { url ->
            when {
                url.endsWith("/page/summary/REM") -> """{"type": "disambiguation", "title": "Rem"}"""
                url.contains("titles=REM") ->
                    """{"query": {"pages": [{"title": "Rapid eye movement sleep", "description": "Phase of sleep characterized by rapid eye movements"},""" +
                        """{"title": "R.E.M.", "description": "American rock band"}, {"title": "Roentgen equivalent man", "description": "Radiation unit"}]}}"""
                url.contains("/page/summary/Rapid%20eye") ->
                    """{"type": "standard", "title": "Rapid eye movement sleep", "extract": "A phase of sleep with quick, random eye movements and vivid dreams.", "content_urls": {"mobile": {"page": "https://en.m.wikipedia.org/wiki/REM_sleep"}}}"""
                url.endsWith("/page/summary/SCN") ->
                    """{"type": "standard", "title": "Suprachiasmatic nucleus", "extract": "The brain's master clock.", "content_urls": {"mobile": {"page": "https://en.m.wikipedia.org/wiki/SCN"}}}"""
                else -> null
            }
        }
    }

    @After
    fun tearDown() {
        LookUp.close()
        Explain.close()
        LookUp.fetchForTest = null
        SummaryController.useForTest(null)
    }

    @Test
    fun aWordIsLookedUpFromWhatWasSaidAndKeptAsANote() {
        val id = lecture()
        compose.onNodeWithText("The Science of Sleep").performClick()
        // A long press on "REM" in what was said.
        val node = compose.onNodeWithText(sleepSaid, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val box = layouts.single().getBoundingBox(sleepSaid.indexOf("REM") + 1)
        node.performTouchInput { longClick(box.center) }
        compose.onNodeWithText("Look up “REM”").performClick()
        settle()

        compose.onNodeWithText("acronym").assertIsDisplayed()
        compose.onNodeWithText("Rapid eye movement sleep, the dreaming stage.").assertIsDisplayed()
        compose.onNodeWithText("said 3 times").assertIsDisplayed()
        compose.onNodeWithText("Rapid eye movement sleep").assertIsDisplayed()
        compose.onNodeWithText("Other meanings: R.E.M. (American rock band) · Roentgen equivalent man (Radiation unit)").assertExists()
        screenshot("38-look-up")

        compose.onNodeWithText("Add as a note").performScrollTo().performClick()
        compose.waitForIdle()
        val note = SessionRepository.get(id)!!.notes.single { it.photo == null }
        assertEquals("REM: Rapid eye movement sleep, the dreaming stage.", note.text)
        assertEquals("r1" to 240_000L, note.recId to note.offsetMs)
    }

    @Test
    fun aSlideIsExplainedAndItsTermsLookedUp() {
        lecture()
        compose.onNodeWithText("The Science of Sleep").performClick()
        compose.onNodeWithText("Your circadian clock").performTouchInput { longClick() }
        compose.onNodeWithText("Explain this slide").performClick()
        settle()

        compose.onNodeWithText("The SCN keeps the body's time, and morning light resets it.").assertIsDisplayed()
        compose.onNodeWithText("Look up").assertIsDisplayed()
        screenshot("39-explain-this-slide")
        compose.onNodeWithText("SCN").performClick()
        settle()
        compose.onNodeWithText("The SCN is the suprachiasmatic nucleus, the brain's clock.").assertIsDisplayed()
        compose.onNodeWithText("Suprachiasmatic nucleus").assertIsDisplayed()
    }

    /** A lecture: REM said three times, and a slide about the body clock. */
    private fun lecture(): String {
        val session = SessionRepository.create("The Science of Sleep")
        SessionRepository.addRecording(
            session.id,
            Recording(
                "r1", "a.m4a", 1_000_000, 3_600_000,
                transcript = listOf(
                    TranscriptSegment(240_000, 247_000, sleepSaid),
                    TranscriptSegment(300_000, 306_000, "REM comes back every ninety minutes."),
                    TranscriptSegment(420_000, 426_000, "Toward morning, REM takes over."),
                    TranscriptSegment(1_262_000, 1_270_000, "Deep in the brain the SCN keeps a roughly 24-hour rhythm."),
                    TranscriptSegment(1_306_000, 1_312_000, "Bright morning light resets it every day."),
                ),
                transcriptModel = "parakeet-unified-en-0.6b",
            ),
        )
        SessionRepository.addNote(session.id, Note("p1", "", 1_001_260_000, "r1", 1_260_000, photo = "photo-p1.jpg", photoText = "Your circadian clock\nThe SCN sets it\nMorning light resets it"))
        compose.waitForIdle()
        return session.id
    }

    private fun settle() {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            val model = CountDownLatch(1)
            SummaryController.afterQueued { model.countDown() }
            assertTrue(model.await(10, TimeUnit.SECONDS))
            val web = CountDownLatch(1)
            LookUp.afterQueued { web.countDown() }
            assertTrue(web.await(10, TimeUnit.SECONDS))
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
