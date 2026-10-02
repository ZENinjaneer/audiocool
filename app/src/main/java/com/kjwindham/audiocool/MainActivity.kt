package com.kjwindham.audiocool

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.NoteFocus
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.playbackStartFor
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.SearchHit
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.ui.AudioCoolTheme
import com.kjwindham.audiocool.ui.SessionListScreen
import com.kjwindham.audiocool.ui.SessionScreen
import com.kjwindham.audiocool.util.Prefs
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

    override fun onStart() {
        super.onStart()
        // Pick up transcription that couldn't start while the app was in the background.
        TranscriptionController.startWorker()
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
    val context = LocalContext.current
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Opened from something said that a search found: show what was said, whatever view was picked last.
    var openOnSpeech by rememberSaveable { mutableStateOf(false) }
    // Kept here so the search is still there when you come back from a result.
    var query by rememberSaveable { mutableStateOf("") }
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
            query = query,
            onQueryChange = { query = it },
            onOpen = {
                openOnSpeech = false
                openId = it
            },
            onOpenHit = { hit ->
                playHit(context, hit)
                openOnSpeech = hit.kind == HitKind.SPEECH
                openId = hit.sessionId
            },
            onCreate = {
                openOnSpeech = false
                openId = SessionRepository.create(defaultSessionTitle()).id
            },
        )
    } else {
        BackHandler { openId = null }
        SessionScreen(session = open, onBack = { openId = null }, showSpeech = openOnSpeech)
    }
}

/** Starts playback at a search result, the same way tapping it inside the session would. */
private fun playHit(context: Context, hit: SearchHit) {
    if (RecorderController.state.value.status != RecorderController.Status.IDLE) return
    val session = SessionRepository.get(hit.sessionId) ?: return
    val rec = session.recording(hit.recId) ?: return
    val at = hit.atMs ?: return
    val note = hit.noteId?.let { id -> session.notes.firstOrNull { it.id == id } }
    if (note != null) {
        val start = playbackStartFor(note, session.notes, Prefs(context).leadInSeconds * 1000L)
        PlayerController.playFrom(session.id, rec, start, NoteFocus(note.id, rec.id, fromMs = start, untilMs = at))
    } else {
        PlayerController.playFrom(session.id, rec, (at - 300).coerceAtLeast(0L))
    }
}
