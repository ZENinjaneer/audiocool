package com.kjwindham.audiocool

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.ui.AudioCoolTheme
import com.kjwindham.audiocool.ui.SessionListScreen
import com.kjwindham.audiocool.ui.SessionScreen
import com.kjwindham.audiocool.util.defaultSessionTitle

class MainActivity : ComponentActivity() {
    /** A session to open, e.g. when the recording notification is tapped. */
    private val requestedSession = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedSession.value = intent?.getStringExtra(EXTRA_SESSION_ID)
        setContent {
            AudioCoolTheme {
                AppRoot(requestedSession)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SESSION_ID)?.let { requestedSession.value = it }
    }

    override fun onStop() {
        super.onStop()
        // No background playback: pause when the app leaves the screen.
        if (!isChangingConfigurations) PlayerController.pause()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

@Composable
private fun AppRoot(requested: MutableState<String?>) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = requested.value
    LaunchedEffect(pending) {
        if (pending != null) {
            openId = pending
            requested.value = null
        }
    }
    val sessions by SessionRepository.sessions.collectAsStateWithLifecycle()
    val open = openId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (open == null) {
        SessionListScreen(
            sessions = sessions,
            onOpen = { openId = it },
            onCreate = { openId = SessionRepository.create(defaultSessionTitle()).id },
        )
    } else {
        BackHandler { openId = null }
        SessionScreen(session = open, onBack = { openId = null })
    }
}
