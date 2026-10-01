package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.RecordingService
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.transcribe.TranscriptionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/** Drives the real UI under Robolectric on Android 14 (the S21's last OS) at a Galaxy S21-like screen size. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordAndNoteFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun cleanUp() {
        RecorderController.stop()
        PlayerController.release()
        TranscriptionController.cancelAll()
    }

    @Test
    fun searchFindsWhatWasSaidAndPlaysFromThere() {
        val session = SessionRepository.create("Bio 101")
        val file = File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        SessionRepository.addRecording(session.id, Recording("r1", file.name, 1_000L, 60_000))
        SessionRepository.setTranscript(
            session.id, "r1",
            listOf(
                TranscriptSegment(2_000, 6_000, "Mitochondria are the powerhouse of the cell."),
                TranscriptSegment(30_000, 34_000, "The Krebs cycle happens in the matrix."),
            ),
        )
        SessionRepository.addNote(session.id, Note("n1", "Krebs = energy", 1_031_000, "r1", 31_000))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(60_000, 0))
        compose.waitForIdle()

        // Search everything from the main screen: the spoken words and the note both match.
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("krebs")
        advance(1)
        // The search runs off the main thread; wait for its results.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 results").assertIsDisplayed()
        screenshot("6-search")

        // Tapping what was said opens the transcript there and plays from just before it.
        compose.onNodeWithText("The Krebs cycle happens in the matrix.").performClick()
        compose.waitForIdle()
        assertTrue(PlayerController.state.value.isPlaying)
        assertEquals(29_700L, PlayerController.state.value.positionMs)
        compose.onNode(hasText("The Krebs cycle happens in the matrix.") and isSelected()).assertExists()
        screenshot("7-transcript")

        // The transcript has its own search.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("powerhouse")
        compose.waitForIdle()
        compose.onNodeWithText("1 match").assertIsDisplayed()
        compose.onNodeWithText("The Krebs cycle happens in the matrix.").assertDoesNotExist()
    }

    @Test
    fun transcribeAsksBeforeDownloadingTheModelThenQueuesTheSession() {
        val session = SessionRepository.create("History")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", 1_000L, 60_000))
        compose.waitForIdle()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Transcript").performClick()
        compose.onNodeWithText("Not transcribed yet").assertIsDisplayed()

        compose.onNodeWithText("Transcribe").performClick()
        compose.onNodeWithText("Download the speech model?").assertIsDisplayed()
        compose.onNodeWithText("Download and transcribe").performClick()
        compose.waitForIdle()

        assertTrue(TranscriptionController.state.value.isPending(session.id, "r1"))
        assertEquals(TranscriptionService::class.java.name, shadowOf(app).nextStartedService.component?.className)
        compose.onNodeWithText("Waiting to transcribe…").assertIsDisplayed()
    }

    @Test
    fun notesLinkToTheRecordingAndTapToPlayFromJustBefore() {
        screenshot("1-empty")
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").assertIsDisplayed()
        screenshot("2-new-session")
        compose.onNodeWithText("Start recording").performClick()
        assertEquals(RecorderController.Status.RECORDING, RecorderController.state.value.status)
        compose.onNodeWithText("REC").assertIsDisplayed()
        assertEquals(RecordingService::class.java.name, shadowOf(app).nextStartedService.component?.className)

        advance(12)
        // A phrase transcribed live shows up under the recording timer.
        SessionRepository.appendTranscriptSegment(
            SessionRepository.sessions.value.single().id, RecorderController.state.value.recId!!,
            TranscriptSegment(2_000, 9_000, "Welcome to the first lecture."),
        )
        compose.onNodeWithText("Welcome to the first lecture.").assertIsDisplayed()
        typeNote("First point")
        compose.onNodeWithText("First point").assertIsDisplayed()
        // The note's timestamp chip, plus the recording timer, which also reads 00:12.
        compose.onAllNodesWithText("00:12").assertCountEquals(2)

        // Paused time isn't in the audio file, so it mustn't move the timestamps either.
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithText("PAUSED").assertIsDisplayed()
        advance(30)
        typeNote("While paused")
        compose.onNodeWithContentDescription("Resume").performClick()
        advance(5)
        typeNote("After resume")
        advance(4)
        compose.onNode(hasSetTextAction()).performTextInput("Typing this one now")
        screenshot("3-recording")
        compose.onNode(hasSetTextAction()).performTextInput("\n")

        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitForIdle()
        assertEquals(RecorderController.Status.IDLE, RecorderController.state.value.status)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()

        val session = SessionRepository.sessions.value.single()
        assertEquals(
            listOf("First point", "While paused", "After resume", "Typing this one now"),
            session.orderedNotes().map { it.text },
        )
        assertNear(listOf(12_000L, 12_000L, 17_000L, 21_000L), session.orderedNotes().map { it.offsetMs!! })
        assertNear(listOf(21_000L), listOf(session.recordings.single().durationMs))

        // Tapping a note jumps to it: playback starts the default 3 s before it, and the tapped
        // note lights up right away (not the previous one, even though the lead-in is before it).
        val (firstPoint, whilePaused, afterResume) = session.orderedNotes()
        val audio = SessionRepository.audioFile(session.id, session.recordings.single())
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(21_000, 0))
        compose.onNodeWithText("After resume").performClick()
        compose.waitForIdle()
        val player = PlayerController.state.value
        assertTrue(player.isPlaying)
        assertEquals(session.recordings.single().id, player.recId)
        assertEquals(afterResume.offsetMs!! - 3_000, player.positionMs)
        compose.onNode(hasText("After resume") and isSelected()).assertExists()
        compose.onNode(hasText("While paused") and isSelected()).assertDoesNotExist()
        screenshot("4-playback")

        // The lead-in never reaches back past the previous note: "While paused" is 15 ms after
        // "First point", so it starts right at "First point" rather than 3 s earlier.
        compose.onNodeWithText("While paused").performClick()
        compose.waitForIdle()
        assertEquals(firstPoint.offsetMs, PlayerController.state.value.positionMs)
        compose.onNode(hasText("While paused") and isSelected()).assertExists()
        compose.onNode(hasText("First point") and isSelected()).assertDoesNotExist()
        assertTrue(whilePaused.offsetMs!! - firstPoint.offsetMs!! < 3_000)

        // A note typed while replaying links to the playback position.
        PlayerController.pause()
        PlayerController.seekTo(3_000)
        typeNote("Added on replay")
        val replayNote = SessionRepository.sessions.value.single().notes.single { it.text == "Added on replay" }
        assertEquals(3_000L, replayNote.offsetMs)
        assertEquals(
            listOf("Added on replay", "First point", "While paused", "After resume", "Typing this one now"),
            SessionRepository.sessions.value.single().orderedNotes().map { it.text },
        )

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("5 notes", substring = true).assertIsDisplayed()
        screenshot("5-session-list")
    }

    @Test
    fun shareSendsMarkdownNotes() {
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        advance(65)
        typeNote("Key idea")
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.onNodeWithContentDescription("Share notes and audio").performClick()

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("- [01:05] Key idea"))
        @Suppress("DEPRECATION")
        val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(streams.first().toString().endsWith(".md"))
    }

    @Test
    fun foregroundServiceShowsOngoingRecordingNotification() {
        val session = SessionRepository.create("Bio 101")
        assertTrue(RecorderController.start(session.id))
        val service = Robolectric.buildService(RecordingService::class.java).create().startCommand(0, 1).get()

        val n = shadowOf(service).lastForegroundNotification
        assertNotNull(n)
        assertEquals("Recording", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Bio 101", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Pause", "Stop"), n.actions.map { it.title.toString() })

        RecorderController.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** The test clock advances a frame or so per UI interaction, so compare times loosely. */
    private fun assertNear(expected: List<Long>, actual: List<Long>, toleranceMs: Long = 250) {
        assertEquals("count", expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertTrue("expected ~$e ms but was $a ms in $actual", kotlin.math.abs(e - a) <= toleranceMs) }
    }

    private fun typeNote(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNode(hasSetTextAction()).performTextInput("\n")
        compose.waitForIdle()
    }

    /** Lets [seconds] pass on the main looper's clock, which also drives SystemClock. */
    private fun advance(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        compose.waitForIdle()
    }

    /** Renders the activity window to build/screenshots/<name>.png. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
