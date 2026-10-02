package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.QuickRecord
import com.kjwindham.audiocool.audio.QuickRecordRestorer
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.RecordingService
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ui.CaptureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock screen's Record button: a notification that starts a recording without unlocking. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickRecordTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val restorer get() = ComponentName(app, QuickRecordRestorer::class.java)

    private fun readyNotification(): Notification? {
        shadowOf(Looper.getMainLooper()).idle()
        return notifications.allNotifications.firstOrNull { it.channelId == "quick_record" }
    }

    @Before
    fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun cleanUp() {
        RecorderController.stop()
        QuickRecord.setEnabled(app, false)
    }

    @Test
    fun itsRecordButtonStartsARecordingAndItComesBackWhenThatStops() {
        assertNull("off until it's turned on", readyNotification())
        QuickRecord.setEnabled(app, true)
        val n = readyNotification()!!
        assertEquals("Ready to record", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Tapping it starts recording with the capture screen open; the button starts without opening anything.
        val tap = shadowOf(n.contentIntent)
        assertTrue(tap.isActivityIntent)
        assertEquals(CaptureActivity.ACTION_START, tap.savedIntent.action)
        val record = shadowOf(n.actions.single().actionIntent)
        assertEquals("● Record", n.actions.single().title.toString())
        assertTrue(record.isServiceIntent)

        val service = Robolectric.buildService(RecordingService::class.java, record.savedIntent).create().startCommand(0, 1)
        assertEquals(RecorderController.Status.RECORDING, RecorderController.state.value.status)
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        val sessionId = RecorderController.state.value.sessionId!!
        assertEquals(1, SessionRepository.get(sessionId)!!.recordings.size)
        assertNull("the recording's own notification takes over", readyNotification())

        // Pressed again while recording (an old copy of the notification): nothing new starts.
        service.withIntent(record.savedIntent).startCommand(0, 2)
        assertEquals(sessionId, RecorderController.state.value.sessionId)

        RecorderController.stop()
        assertNotNull("back once recording stops", readyNotification())
    }

    @Test
    fun itComesBackAfterARestartOnlyWhileItsOn() {
        QuickRecord.setEnabled(app, true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getComponentEnabledSetting(restorer))
        app.getSystemService(NotificationManager::class.java).cancelAll()
        QuickRecordRestorer().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(readyNotification())

        QuickRecord.setEnabled(app, false)
        assertNull(readyNotification())
        // Not woken at startup at all while it's off.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(restorer))
    }

    @Test
    fun withoutTheMicrophoneThereIsNoButton() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        QuickRecord.setEnabled(app, true)
        assertTrue(QuickRecord.isEnabled(app))
        assertNull(readyNotification())
        assertFalse(notifications.allNotifications.any { it.channelId == "quick_record" })
    }
}
