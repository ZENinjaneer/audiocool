package com.kjwindham.audiocool

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.Dictation
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.RecordingService
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.ocr.SlideText
import com.kjwindham.audiocool.ocr.TextLine
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.transcribe.TranscriptionService
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

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
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)
        // FileProvider remembers the app's folders in a static cache, but each test gets new ones.
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>).clear()
    }

    @After
    fun cleanUp() {
        SlideText.readerForTest = null
        RecorderController.stop()
        PlayerController.release()
        TranscriptionController.cancelAll()
        Dictation.recognizeForTest = null
        ShadowAudioRecord.clearSource()
    }

    @Test
    fun aPhotoOfTheSlideIsLinkedToTheMomentAndBecomesTheThumbnail() {
        // What ML Kit would read off the slide.
        SlideText.readerForTest = {
            listOf(
                TextLine("Why on-device speech?", 100, 100, 1500, 220, 100, 0.95f),
                TextLine("Latency under 50 ms", 140, 320, 900, 370, 45, 0.95f),
                TextLine("Works offline, on a plane or in a basement", 140, 400, 1400, 450, 45, 0.95f),
                TextLine("Nothing leaves the phone", 140, 480, 1000, 530, 45, 0.95f),
                TextLine("Costs nothing per minute", 140, 560, 1000, 610, 45, 0.95f),
            )
        }
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        advance(20)
        compose.onNodeWithContentDescription("Take a photo").performClick()

        // Play the camera app: save a "slide" where it was asked to, and report success.
        val request = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.action)
        @Suppress("DEPRECATION")
        val output = request.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        val slide = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(android.graphics.Color.rgb(20, 40, 120))
                drawRect(100f, 100f, 1500f, 260f, Paint().apply { color = android.graphics.Color.WHITE })
            }
        }
        File(app.cacheDir, "capture/${output.lastPathSegment}").outputStream().use { slide.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        shadowOf(compose.activity).receiveResult(request, Activity.RESULT_OK, Intent())
        compose.waitUntil(10_000) { SessionRepository.sessions.value.single().notes.any { it.photo != null } }

        val photo = SessionRepository.sessions.value.single().notes.single()
        assertEquals(RecorderController.state.value.recId, photo.recId)
        assertTrue("linked at ${photo.offsetMs}", photo.offsetMs!! in 19_000L..21_500L)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Thumbnail").assertIsDisplayed()
        // What the slide says is part of the note: a few lines, all of it once tapped.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Text in photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Latency under 50 ms", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Show all").assertIsDisplayed()
        // And the slide's title named the session (the top bar).
        assertEquals("Why on-device speech?", SessionRepository.sessions.value.single().title)
        screenshot("9-photo-note")
        compose.onNodeWithText("Show all").performClick()
        compose.onNodeWithText("Show less").assertIsDisplayed()

        // While recording, tapping the picture (not its text, which expands or shrinks) shows it full screen,
        // with a way to hear what was being said.
        compose.onAllNodesWithContentDescription("Photo", useUnmergedTree = true).onFirst().performClick()
        compose.onNodeWithText("Play from", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()

        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("1 photo", substring = true).assertIsDisplayed()
        letPhotosLoad()
        screenshot("10-list-with-thumbnail")
        compose.onNodeWithContentDescription("Show as a gallery").performClick()
        compose.onNodeWithContentDescription("Show as a list").assertIsDisplayed()
        assertTrue(Prefs(app).galleryView)
        letPhotosLoad()
        screenshot("11-gallery")
    }

    @Test
    fun theCameraAsksForItsPermissionFirstRatherThanCrashing() {
        // 1.7 to 1.9 opened the camera app straight away. An app that declares the camera permission (this
        // one does, for the lock screen's camera) may only do that once it's granted: otherwise Android
        // throws, and the app crashed. (Robolectric doesn't throw, so the test checks the order instead.)
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        advance(5)
        compose.onNodeWithContentDescription("Take a photo").performClick()
        // What opened was Android's prompt for the camera, not the camera app.
        val prompt = permissionPrompt(Manifest.permission.CAMERA)

        // Declined: it says how to allow it, and the recording carries on.
        answer(prompt, PackageManager.PERMISSION_DENIED)
        compose.onNodeWithText("To take photos, allow AudioCool to use the camera", substring = true).assertIsDisplayed()
        assertNull(shadowOf(compose.activity).peekNextStartedActivityForResult())
        assertEquals(RecorderController.Status.RECORDING, RecorderController.state.value.status)
        // Android stops asking after a second no, so the message has a way to the app's settings.
        shadowOf(compose.activity).clearNextStartedActivities()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        val settings = shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${app.packageName}", settings.dataString)
        shadowOf(compose.activity).clearNextStartedActivities()

        // Allowed: the camera app opens, for the moment the button was tapped.
        advance(5)
        compose.onNodeWithContentDescription("Take a photo").performClick()
        val again = permissionPrompt(Manifest.permission.CAMERA)
        // Time spent on the prompt doesn't move the photo.
        advance(3)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        answer(again, PackageManager.PERMISSION_GRANTED)
        val request = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.action)
        @Suppress("DEPRECATION")
        val output = request.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        val slide = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
        File(app.cacheDir, "capture/${output.lastPathSegment}").outputStream().use { slide.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        shadowOf(compose.activity).receiveResult(request, Activity.RESULT_OK, Intent())
        compose.waitUntil(10_000) { SessionRepository.sessions.value.single().notes.any { it.photo != null } }
        val photo = SessionRepository.sessions.value.single().notes.single { it.photo != null }
        assertTrue("linked at ${photo.offsetMs}", photo.offsetMs!! in 9_000L..11_500L)
    }

    @Test
    fun photosWhileTranscribingLiveKeepTheTimelineTogether() {
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        val sessionId = SessionRepository.sessions.value.single().id
        val recId = RecorderController.state.value.recId!!
        var at = 0L
        // Live transcription adds a phrase every few seconds, as it does while recording.
        fun speak(seconds: Long, text: String) {
            advance(seconds)
            SessionRepository.appendTranscriptSegment(sessionId, recId, TranscriptSegment(at, at + seconds * 1000 - 300, text))
            at += seconds * 1000
            compose.waitForIdle()
        }
        fun photo() {
            compose.onNodeWithContentDescription("Take a photo").performClick()
            val request = shadowOf(compose.activity).nextStartedActivityForResult.intent
            @Suppress("DEPRECATION")
            val output = request.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
            val slide = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
            File(app.cacheDir, "capture/${output.lastPathSegment}").outputStream().use { slide.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            shadowOf(compose.activity).receiveResult(request, Activity.RESULT_OK, Intent())
            val before = SessionRepository.get(sessionId)!!.notes.count { it.photo != null }
            compose.waitUntil(10_000) { SessionRepository.get(sessionId)!!.notes.count { it.photo != null } > before }
        }
        speak(6, "Good morning, everyone.")
        speak(8, "Today we look at how phones run models.")
        photo()
        // The phrase under way when the photo was taken, then more after it.
        speak(9, "First, memory is the real limit.")
        typeNote("Memory first")
        speak(7, "Second, the battery.")
        photo()
        photo()
        speak(30, "A long stretch about quantization and calibration and what goes wrong without it.")
        compose.onNodeWithContentDescription("Mark this moment").performClick()
        speak(4, "Questions?")
        compose.waitForIdle()
        assertTrue(SessionRepository.get(sessionId)!!.notes.any { it.text == "Memory first" })
        assertEquals(3, SessionRepository.get(sessionId)!!.notes.count { it.photo != null })
        screenshot("19-live-photos")

        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitForIdle()
        // Every view of it holds together.
        for (view in listOf("Notes + context", "Notes only", "Everything")) {
            compose.onNodeWithText(view).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(view).assertIsSelected()
        }
    }

    @Test
    fun theMainScreenOffersTheSpeechModelDownload() {
        compose.onNodeWithText("Set up transcription").assertIsDisplayed()
        screenshot("8-set-up-transcription")
        compose.onNodeWithText("Download").performClick()
        assertTrue(startedTranscriptionService())
        TranscriptionController.cancelAll()

        // "Not now" puts it away for good.
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Set up transcription").assertDoesNotExist()
        assertTrue(Prefs(app).speechModelOfferDismissed)
    }

    @Test
    fun theModelCanDownloadMidRecordingAndThenTranscribesThatRecordingLive() {
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        compose.onNodeWithText("Live transcription needs the speech model", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithText("Download the speech model?").assertIsDisplayed()
        compose.onNode(hasText("Download") and hasAnyAncestor(isDialog())).performClick()
        // The download starts now; it doesn't wait for the recording to end.
        assertTrue(startedTranscriptionService())

        // When it's done, live transcription of this recording starts (here it fails at once,
        // with no speech engine on the test machine, and hands the recording to the queue).
        val rec = RecorderController.state.value
        TranscriptionController.modelDownloaded()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(5_000) { TranscriptionController.state.value.isPending(rec.sessionId!!, rec.recId!!) }
        compose.onNodeWithText("Live transcription needs the speech model", substring = true).assertDoesNotExist()
    }

    /** Photos are decoded off the main thread, which the test clock doesn't wait for. */
    private fun letPhotosLoad() = repeat(10) {
        Thread.sleep(50)
        compose.waitForIdle()
    }

    private fun startedTranscriptionService(): Boolean =
        generateSequence { shadowOf(app).nextStartedService }.any { it.component?.className == TranscriptionService::class.java.name }

    @Test
    fun withAHeadsetPluggedInThePhoneMicKeepsRecordingTheRoom() {
        // (Robolectric can't fake the phone's own mic, only headsets, so "mic" here means the phone's.)
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        compose.onNodeWithText("Record with").assertDoesNotExist()

        // A Bluetooth headset is for your own voice, so it isn't offered for recording the room.
        audio.addInputDevice(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build(), true)
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("Record with").assertDoesNotExist()

        // Plugging in a headset doesn't take the recording over, but it can be chosen instead.
        audio.addInputDevice(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_HEADSET).build(), true)
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("Record with").assertIsDisplayed()
        assertFalse(RecorderController.state.value.usingExternalMic)
        assertEquals("mic", RecorderController.state.value.mic)
        screenshot("7-record-with")
        compose.onNodeWithText("Headset mic").performClick()
        assertTrue(RecorderController.state.value.usingExternalMic)
        assertEquals("headset mic", RecorderController.state.value.mic)
        compose.onNodeWithText("Phone mic").performClick()
        assertFalse(RecorderController.state.value.usingExternalMic)
    }

    @Test
    fun aSpokenNoteIsTranscribedAndLinkedToWhenYouStartedTalking() {
        TranscriptionController.autoTranscribe = false
        TranscriptionController.modelDownloaded()
        // A tone stands in for your voice, arriving in real time; the speech model is stubbed out.
        var phase = 0.0
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                for (i in 0 until sizeInShorts) {
                    audioData[offsetInShorts + i] = (8_000 * sin(phase)).toInt().toShort()
                    phase += 2 * PI * 440 / 16_000
                }
                Thread.sleep(sizeInShorts * 1_000L / 16_000)
                return sizeInShorts
            }
        })
        val heardSamples = AtomicInteger()
        Dictation.recognizeForTest = {
            heardSamples.set(it.size)
            "Ask whether this is on the exam"
        }

        compose.onNodeWithContentDescription("New session").performClick()
        compose.onNodeWithText("Start recording").performClick()
        advance(30)
        // A tap listens hands-free; the next tap adds the note.
        compose.onNodeWithContentDescription("Speak a note").performClick()
        compose.onNodeWithText("Listening on the", substring = true).assertIsDisplayed()
        Thread.sleep(700)
        screenshot("6-dictating")
        compose.onNodeWithContentDescription("Stop and add the spoken note").performClick()
        compose.waitUntil(5_000) { SessionRepository.sessions.value.single().notes.isNotEmpty() }

        val note = SessionRepository.sessions.value.single().notes.single()
        assertEquals("Ask whether this is on the exam", note.text)
        assertEquals(RecorderController.state.value.recId, note.recId)
        assertTrue("linked at ${note.offsetMs}", note.offsetMs!! in 29_000L..31_500L)
        assertTrue("heard ${heardSamples.get()} samples", heardSamples.get() >= 16_000 / 2)
        compose.onNodeWithText("Ask whether this is on the exam").assertIsDisplayed()
        // The recording carried on throughout.
        assertEquals(RecorderController.Status.RECORDING, RecorderController.state.value.status)
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

        // Tapping what was said opens the session's timeline there, lit up, and plays from just before it.
        compose.onNodeWithText("The Krebs cycle happens in the matrix.").performClick()
        compose.waitForIdle()
        assertTrue(PlayerController.state.value.isPlaying)
        assertEquals(29_700L, PlayerController.state.value.positionMs)
        compose.onNode(hasText("The Krebs cycle happens in the matrix.") and isSelected()).assertExists()
        // The note written while it was said comes right after it.
        compose.onNodeWithText("Krebs = energy").assertIsDisplayed()
        screenshot("7-transcript")

        // The session has its own search, over notes, photos and what was said.
        compose.onNodeWithContentDescription("Search this session").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("powerhouse")
        compose.waitForIdle()
        compose.onNodeWithText("1 match").assertIsDisplayed()
        compose.onNodeWithText("The Krebs cycle happens in the matrix.").assertDoesNotExist()
        compose.onNodeWithText("Mitochondria are the powerhouse of the cell.").performClick()
        compose.waitForIdle()
        assertEquals(1_700L, PlayerController.state.value.positionMs)
    }

    @Test
    fun transcriptsFromTheOldModelCanBeRedone() {
        val session = SessionRepository.create("Chem")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", 1_000L, 60_000))
        // Made before this version (no model recorded): the old, less accurate model.
        SessionRepository.setTranscript(session.id, "r1", listOf(TranscriptSegment(1_000, 3_000, "old words")))
        compose.waitForIdle()
        compose.onNodeWithText("Chem").performClick()
        compose.onNodeWithText("Made with the older, less accurate speech model.").assertIsDisplayed()

        compose.onNodeWithText("Transcribe again").performClick()
        compose.onNodeWithText("Download and transcribe").performClick()
        compose.waitForIdle()
        assertTrue(TranscriptionController.state.value.isPending(session.id, "r1"))
    }

    @Test
    fun transcribeAsksBeforeDownloadingTheModelThenQueuesTheSession() {
        val session = SessionRepository.create("History")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", 1_000L, 60_000))
        compose.waitForIdle()
        compose.onNodeWithText("History").performClick()
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
        // Linked to 00:12, where the recording timer is too.
        compose.onNodeWithText("Note · 00:12").assertIsDisplayed()
        compose.onNodeWithText("00:12").assertIsDisplayed()

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
        assertEquals(listOf("★ Mark", "Pause", "Stop"), n.actions.map { it.title.toString() })

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
    /** The permission prompt just opened, which must be asking for [permission] (and nothing has opened since). */
    private fun permissionPrompt(permission: String): Intent {
        val prompt = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", prompt.action)
        assertEquals(listOf(permission), shadowOf(compose.activity).lastRequestedPermission.requestedPermissions.toList())
        assertNull(shadowOf(compose.activity).peekNextStartedActivityForResult())
        return prompt
    }

    /** Answers [prompt] as Android does, through the activity's results, after which the app may ask again. */
    private fun answer(prompt: Intent, result: Int) {
        val asked = shadowOf(compose.activity).lastRequestedPermission.requestedPermissions
        shadowOf(compose.activity).receiveResult(
            prompt, Activity.RESULT_OK,
            Intent()
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", asked)
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", IntArray(asked.size) { result }),
        )
        compose.waitForIdle()
    }

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
