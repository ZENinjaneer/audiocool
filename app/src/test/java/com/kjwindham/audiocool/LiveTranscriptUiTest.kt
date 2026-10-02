package com.kjwindham.audiocool

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.ui.AudioCoolTheme
import com.kjwindham.audiocool.ui.TimelinePane
import com.kjwindham.audiocool.ui.TranscriptStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTranscriptUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        "s1", "Bio 101", 0, 0,
        recordings = listOf(
            Recording(
                "r1", "recording-1.aac", 1_000, durationMs = 0, // still recording
                transcript = listOf(
                    TranscriptSegment(2_000, 6_000, "Today we're going to look at how cells make energy."),
                    TranscriptSegment(7_000, 12_000, "The Krebs cycle happens in the mitochondrial matrix."),
                ),
                transcriptModel = "parakeet-unified-en-0.6b",
            ),
        ),
    )

    private fun show(live: LiveTranscription.State, desktop: DesktopSync.Progress? = null) = compose.setContent {
        AudioCoolTheme {
            Surface(Modifier.fillMaxSize()) {
                TimelinePane(
                    session = session,
                    rows = timelineRows(session, TimelineMode.EVERYTHING),
                    playingKey = null,
                    focusId = null,
                    peekId = null,
                    followKey = null,
                    follow = false,
                    engaged = false,
                    canPlay = false,
                    liveTail = true,
                    jumpTo = null,
                    onJumpDone = {},
                    header = {
                        TranscriptStatus(
                            session = session,
                            transcription = TranscriptionController.State(modelReady = true),
                            live = live,
                            desktopProgress = desktop,
                            recordingHere = true,
                            onTranscribe = {},
                            onRetranscribe = {},
                        )
                    },
                    empty = null,
                    onPlaySpeech = {},
                    onPlayNote = {},
                    onOpenPhoto = {},
                    onEdit = {},
                    onFold = {},
                )
            }
        }
    }

    @Test
    fun linesAppearWhileRecordingWithAListeningIndicator() {
        show(LiveTranscription.State("s1", "r1", speaking = true))
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        // The two phrases, a second apart, read as one paragraph.
        compose.onNodeWithText("cells make energy. The Krebs cycle happens in the mitochondrial matrix.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Transcribed with Parakeet 0.6B on this phone").assertIsDisplayed()
        screenshot("8-live-transcript")
    }

    @Test
    fun showsDesktopProgress() {
        show(LiveTranscription.State(), DesktopSync.Progress(DesktopSync.Phase.TRANSCRIBING, 0.42f))
        compose.onNodeWithText("Transcribing on your desktop… 42%").assertIsDisplayed()
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
