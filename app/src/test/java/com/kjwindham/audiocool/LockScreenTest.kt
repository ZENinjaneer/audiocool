package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.RecordTileService
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.RecordingService
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ui.CaptureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/** Controlling a recording from the lock screen: the capture screen, the notification and the tile. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val keyguard get() = shadowOf(app.getSystemService(KeyguardManager::class.java))

    @Before
    fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun cleanUp() {
        RecorderController.stop()
        keyguard.setKeyguardLocked(false)
    }

    @Test
    fun theTileStartsARecordingOnTheLockScreenWhereYouCanMarkAndStop() {
        keyguard.setKeyguardLocked(true)
        val intent = Intent(app, CaptureActivity::class.java).setAction(CaptureActivity.ACTION_START)
        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            assertEquals(RecorderController.Status.RECORDING, RecorderController.state.value.status)
            val sessionId = RecorderController.state.value.sessionId!!
            compose.onNodeWithText("Recording ·", substring = true).assertIsDisplayed()
            // The camera needs a yes first (asked for in the app, or right here).
            compose.onNodeWithText("Allow camera").assertIsDisplayed()

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            compose.onNodeWithContentDescription("Mark this moment").performClick()
            val mark = SessionRepository.get(sessionId)!!.notes.single()
            assertEquals("★ Marked", mark.text)
            assertTrue("marked at ${mark.offsetMs}", mark.offsetMs!! in 11_000L..13_500L)
            compose.onNodeWithText("★ Marked at", substring = true).assertIsDisplayed()
            scenario.onActivity { screenshot(it, "12-lock-screen-capture") }

            compose.onNodeWithContentDescription("Stop recording").performClick()
            assertEquals(RecorderController.Status.IDLE, RecorderController.state.value.status)
            compose.onNodeWithText("Recording saved").assertIsDisplayed()
        }
    }

    @Test
    fun unlockedItGoesStraightToTheSession() {
        val session = SessionRepository.create("Bio 101")
        assertTrue(RecorderController.start(session.id))
        val intent = Intent(app, CaptureActivity::class.java).setAction(CaptureActivity.ACTION_OPEN)
        ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
            val next = generateSequence { shadowOf(app).nextStartedActivity }.first { it.component?.className == MainActivity::class.java.name }
            assertEquals(session.id, next.getStringExtra(MainActivity.EXTRA_SESSION_ID))
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun theNotificationHasMarkPauseAndStopAndHidesTheNameOnTheLockScreen() {
        val session = SessionRepository.create("Board meeting")
        assertTrue(RecorderController.start(session.id))
        val controller = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1)
        val n = shadowOf(controller.get()).lastForegroundNotification
        assertEquals(listOf("★ Mark", "Pause", "Stop"), n.actions.map { it.title.toString() })
        assertEquals("Board meeting", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("AudioCool", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(3, n.publicVersion.actions.size)

        // Tapping it opens the capture screen; the buttons don't open anything (that would need an unlock).
        val tap = shadowOf(n.contentIntent)
        assertTrue(tap.isActivityIntent)
        assertEquals(CaptureActivity.ACTION_OPEN, tap.savedIntent.action)
        assertTrue(n.actions.none { shadowOf(it.actionIntent).isActivityIntent })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        controller.withIntent(shadowOf(n.actions[0].actionIntent).savedIntent).startCommand(0, 2)
        val mark = SessionRepository.get(session.id)!!.notes.single()
        assertEquals("★ Marked", mark.text)
        assertTrue("marked at ${mark.offsetMs}", mark.offsetMs!! in 7_000L..9_500L)
        shadowOf(Looper.getMainLooper()).idle()
        val updated = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications.last()
        assertTrue(updated.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("★ at 00:08"))
    }

    @Test
    fun theTileStopsARecording() {
        val session = SessionRepository.create("Bio 101")
        assertTrue(RecorderController.start(session.id))
        Robolectric.buildService(RecordTileService::class.java).create().get().onClick()
        assertEquals(RecorderController.Status.IDLE, RecorderController.state.value.status)
    }

    private fun screenshot(activity: CaptureActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
