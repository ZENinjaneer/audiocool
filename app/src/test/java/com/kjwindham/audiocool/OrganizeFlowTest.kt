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
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.summarize.Organizer
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
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The summary model suggesting folders to review, and filing new sessions; the model is a stand-in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganizeFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Sorts by what the titles say, as the model would: lectures on the brain, and chemistry. */
    private inner class FakeGemma : Summarizer {
        override val where = "CPU (4 threads)"
        override val lastSpeed = 300.0 to 12.0

        override fun reply(prompt: String, maxTokens: Int): String {
            if (prompt.contains("Recordings to sort")) {
                val numbered = Regex("""(?m)^(\d+)\. "(.*?)"""").findAll(prompt).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
                val brain = numbered.filter { it.second.startsWith("Neuro") }.map { it.first }
                val chem = numbered.filter { it.second.startsWith("Chem") }.map { it.first }
                return """{"folders": [{"name": "Neuroscience 214", "description": "Lectures on the brain", "recordings": $brain}, """ +
                    """{"name": "Organic Chem", "description": "Reactions", "recordings": $chem}]}"""
            }
            if (prompt.contains("Which folder does it belong in")) {
                val brain = Regex("""(?m)^(\d+)\. Neuroscience 214""").find(prompt)?.groupValues?.get(1) ?: "0"
                return if (prompt.substringAfter("A new recording:").contains("Neuro")) brain else "0"
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
        SummaryController.useForTest { FakeGemma() }
    }

    @After
    fun tearDown() {
        SummaryController.useForTest(null)
    }

    @Test
    fun theModelSuggestsFoldersAndNothingMovesUntilYouSaySo() {
        val sleep = summarized("Neuro: sleep cycles")
        val clock = summarized("Neuro: the circadian clock")
        val substitution = summarized("Chem: substitution")
        summarized("Chem: elimination")
        val book = summarized("Book club")
        letTheModelWork()
        assertEquals(listOf("Neuroscience 214", "Organic Chem"), Organizer.suggestions.value!!.map { it.name })
        assertTrue(SessionRepository.sessions.value.all { it.folder == null })

        compose.onNodeWithText("✦ Organize your sessions?").assertIsDisplayed()
        compose.onNodeWithText("4 sessions fall into two groups: Neuroscience 214 and Organic Chem.").assertIsDisplayed()
        compose.onNodeWithText("See the folders").performClick()
        compose.onNodeWithText("Suggested folders").assertIsDisplayed()
        compose.onNodeWithText("1 session doesn't fit any of these, so it stays where it is.").assertExists()
        screenshot("29-organize-card")
        dialogScreenshot("30-suggested-folders")

        // Leave one out, and organize the rest.
        compose.onNodeWithContentDescription("Include Organic Chem").performClick()
        compose.onNodeWithText("Organize 2 sessions").performClick()
        compose.waitForIdle()
        assertEquals("Neuroscience 214", SessionRepository.get(sleep)!!.folder)
        assertEquals("Neuroscience 214", SessionRepository.get(clock)!!.folder)
        assertNull(SessionRepository.get(substitution)!!.folder)
        assertNull(SessionRepository.get(book)!!.folder)
        assertEquals("Lectures on the brain", FolderRepository.folders.value.single { it.name == "Neuroscience 214" }.description)
        assertTrue(Prefs(app).autoFile)
        compose.onNodeWithText("✦ Organize your sessions?").assertDoesNotExist()

        // And undone.
        compose.onNodeWithText("Organized 2 sessions").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertNull(SessionRepository.get(sleep)!!.folder)
        assertTrue(FolderRepository.folders.value.none { it.name == "Neuroscience 214" })
    }

    @Test
    fun keptOrganizedANewSessionIsFiledOnceItsSummaryIsReady() {
        FolderRepository.create("Neuroscience 214")
        SessionRepository.moveToFolder(summarized("Neuro: sleep cycles"), "Neuroscience 214")
        Prefs(app).autoFile = true
        Prefs(app).autoFileSince = System.currentTimeMillis() - 1
        val memory = summarized("Neuro: memory")
        val groceries = summarized("Groceries")
        letTheModelWork()

        assertEquals("Neuroscience 214", SessionRepository.get(memory)!!.folder)
        // The model found no folder for this one, and won't be asked again.
        assertNull(SessionRepository.get(groceries)!!.folder)
        assertTrue(groceries in Prefs(app).autoFileDone)
        compose.onNodeWithText("Filed “Neuro: memory” in Neuroscience 214").assertIsDisplayed()
    }

    private fun summarized(title: String): String {
        val session = SessionRepository.create(title)
        SessionRepository.setSessionSummary(session.id, SessionSummary("About ${title.lowercase()}.", basis = "", model = "test", createdAt = 0))
        return session.id
    }

    /** Lets the controller notice (it waits a moment for changes to settle) and work through everything. */
    private fun letTheModelWork() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val done = CountDownLatch(1)
            SummaryController.afterQueued { done.countDown() }
            done.await(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    /** The dialog showing, which is a window of its own. */
    private fun dialogScreenshot(name: String) {
        compose.waitForIdle()
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
