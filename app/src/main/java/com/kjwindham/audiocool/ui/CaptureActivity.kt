package com.kjwindham.audiocool.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.kjwindham.audiocool.MainActivity
import com.kjwindham.audiocool.audio.RecorderController

/**
 * What the recording notification and the quick settings tile open. It works over the lock screen:
 * take photos, speak a note, mark the moment, pause or stop, all without unlocking, and it shows
 * nothing else from the app. When the phone isn't locked it goes straight to the session instead.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // The lock screen's short timeout would otherwise turn the screen off mid-photo.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        // A rotation recreates the screen; only act on the intent the first time.
        if (savedInstanceState == null && !route(intent)) return
        setContent {
            AudioCoolTheme {
                CaptureScreen(onDone = ::finish, onOpenApp = ::openApp)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    /** Starts a recording if asked to; goes to the app instead when the phone is unlocked. False if this screen closed. */
    private fun route(intent: Intent?): Boolean {
        var sessionId = RecorderController.state.value.sessionId
        if (intent?.action == ACTION_START && RecorderController.state.value.status == RecorderController.Status.IDLE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                openApp(null) // the app asks for the microphone first
                return false
            }
            sessionId = RecorderController.startNewSession() ?: run {
                finish()
                return false
            }
        }
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            openApp(sessionId)
            return false
        }
        return true
    }

    /** Opens the session in the app, asking to unlock first if the phone is locked. */
    private fun openApp(sessionId: String?) {
        val open = {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            open()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open()
            },
        )
    }

    companion object {
        /** Tapping the recording notification. */
        const val ACTION_OPEN = "com.kjwindham.audiocool.action.CAPTURE"

        /** The quick settings tile: start recording into a new session. */
        const val ACTION_START = "com.kjwindham.audiocool.action.QUICK_RECORD"
    }
}
