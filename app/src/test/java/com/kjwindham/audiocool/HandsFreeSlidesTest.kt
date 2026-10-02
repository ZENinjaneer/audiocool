package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ocr.SlideText
import com.kjwindham.audiocool.ocr.TextLine
import com.kjwindham.audiocool.slides.AutoSlides
import com.kjwindham.audiocool.slides.SlideWatcher
import com.kjwindham.audiocool.ui.CaptureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Hands-free slides: the photos it saves and retakes, and the capture screen's Auto slides. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandsFreeSlidesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        AutoSlides.resetForTest()
        SlideText.readerForTest = { file -> listOf(TextLine("Read from ${file.name}", 0, 0, 100, 20, 20, 0.9f)) }
    }

    @After
    fun tearDown() {
        RecorderController.stop()
        SlideText.readerForTest = null
        AutoSlides.resetForTest()
    }

    @Test
    fun aNewSlideLandsWhereItWentUpAndABetterPictureTakesItsPlace() {
        val session = SessionRepository.create("Sleep")
        AutoSlides.start(session.id)
        val first = picture("first")
        var saved: Boolean? = null
        AutoSlides.save(app, session.id, "r1", SlideWatcher.Decision.New(0, 0), first, 12_000) { saved = it }
        settle()
        assertEquals(true, saved)
        val note = SessionRepository.get(session.id)!!.notes.single()
        assertEquals("r1", note.recId)
        assertEquals(12_000L, note.offsetMs)
        assertFalse(first.exists())
        assertEquals(1, AutoSlides.state.value.slides)

        // More appeared on it: the same note, in the same place, with the new picture (read again).
        AutoSlides.save(app, session.id, "r1", SlideWatcher.Decision.Better(0), picture("second"), null)
        settle()
        val retaken = SessionRepository.get(session.id)!!.notes.single()
        assertEquals(note.id, retaken.id)
        assertEquals(12_000L, retaken.offsetMs)
        assertNotEquals(note.photo, retaken.photo)
        assertFalse(SessionRepository.photoFile(session.id, note.photo!!).exists())
        assertTrue(SessionRepository.photoFile(session.id, retaken.photo!!).exists())
        assertEquals("Read from ${retaken.photo}", retaken.photoText)
        assertEquals(1, AutoSlides.state.value.slides)
    }

    @Test
    fun aSlidesPhotoYouDeletedIsntBroughtBack() {
        val session = SessionRepository.create("Sleep")
        AutoSlides.start(session.id)
        AutoSlides.save(app, session.id, "r1", SlideWatcher.Decision.New(0, 0), picture("first"), 5_000)
        settle()
        SessionRepository.deleteNote(session.id, SessionRepository.get(session.id)!!.notes.single().id)
        val better = picture("second")
        var saved: Boolean? = null
        AutoSlides.save(app, session.id, "r1", SlideWatcher.Decision.Better(0), better, null) { saved = it }
        settle()
        assertEquals(false, saved)
        assertTrue(SessionRepository.get(session.id)!!.notes.isEmpty())
        assertFalse(better.exists())
    }

    @Test
    fun theSessionOpensHandsFreeSlidesWhichWatchesWithTheScreenUnlocked() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val session = SessionRepository.create("Bio 101")
        assertTrue(RecorderController.start(session.id))
        ActivityScenario.launch<CaptureActivity>(Intent(app, CaptureActivity::class.java).setAction(CaptureActivity.ACTION_AUTO_SLIDES)).use { scenario ->
            // Unlocked, it stays: it was asked for.
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            compose.onNodeWithText("Auto slides").assertIsSelected()
            compose.onNodeWithText("Watching for slides").assertIsDisplayed()
            compose.onNodeWithText("Prop the phone up facing the screen", substring = true).assertIsDisplayed()
            scenario.onActivity { screenshot(it, "32-hands-free-slides") }
            assertTrue(AutoSlides.state.value.watching)
            assertEquals(session.id, AutoSlides.state.value.sessionId)

            // A slide saved.
            AutoSlides.save(app, session.id, RecorderController.state.value.recId, SlideWatcher.Decision.New(0, 0), picture("slide"), 1_000)
            settle()
            compose.onNodeWithText("Watching the screen · 1 slide · just now").assertIsDisplayed()
            scenario.onActivity { screenshot(it, "33-hands-free-slides-saved") }
            compose.onNodeWithText("Prop the phone up facing the screen", substring = true).assertDoesNotExist()

            // Paused, it waits; the photo button is back with Photo.
            compose.onNodeWithContentDescription("Pause recording").performClick()
            compose.onNodeWithText("Paused · slides are watched for again when you resume").assertIsDisplayed()
            assertFalse(AutoSlides.state.value.watching)
            compose.onNodeWithText("Photo").performClick()
            compose.onNodeWithContentDescription("Take a photo").assertIsDisplayed()
        }
    }

    @Test
    fun itsOnlyForARecordingInProgress() {
        ActivityScenario.launch<CaptureActivity>(Intent(app, CaptureActivity::class.java).setAction(CaptureActivity.ACTION_AUTO_SLIDES)).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun theSessionsMenuHasItWhileItRecords() {
        val session = SessionRepository.create("Bio 101")
        assertTrue(RecorderController.start(session.id))
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, session.id)).use {
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Hands-free slides").performClick()
            val next = generateSequence { shadowOf(app).nextStartedActivity }.first { it.component?.className == CaptureActivity::class.java.name }
            assertEquals(CaptureActivity.ACTION_AUTO_SLIDES, next.action)
        }
    }

    private fun screenshot(activity: android.app.Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A small JPEG, as the camera would save. */
    private fun picture(name: String): File {
        val file = File(app.cacheDir, "$name.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /** Lets the saving, the reading of the text and the screen catch up. */
    private fun settle() {
        val saved = CountDownLatch(1)
        AutoSlides.afterQueued { saved.countDown() }
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        val read = CountDownLatch(1)
        SlideText.afterQueued { read.countDown() }
        assertTrue(read.await(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }
}
