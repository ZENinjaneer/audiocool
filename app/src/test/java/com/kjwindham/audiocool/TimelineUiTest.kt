package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.Waveform
import com.kjwindham.audiocool.data.MARK_TEXT
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** The session as one timeline: its three views, previews from the scrubber's markers, and scrubbing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val recId = "talk-" + System.nanoTime()
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).timelineMode = "EVERYTHING"
        val session = SessionRepository.create("Scaling Inference on the Edge")
        sessionId = session.id
        val dir = SessionRepository.sessionDir(session.id).apply { mkdirs() }
        val audio = File(dir, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(372_000, 0))
        SessionRepository.addRecording(session.id, Recording(recId, audio.name, 1_000_000, 372_000))
        SessionRepository.setTranscript(session.id, recId, talk)
        // Loudness with pauses between phrases, as the meter would have saved it.
        File(dir, "levels-$recId.bin").writeBytes(
            ByteArray(3720) { i ->
                val speaking = talk.any { i * 100L in it.startMs..it.endMs }
                (if (speaking) 150 + 60 * sin(i / 23.0) + 25 * sin(i / 3.1) else 25.0).toInt().coerceIn(0, 255).toByte()
            },
        )
        slide("s1", 4, "Scaling Inference on the Edge", "Dana Ruiz · Platform Team")
        slide("s2", 96, "Quantization 101", "8-bit and 4-bit weights", "Smaller is faster: memory beats math")
        slide("s3", 198, "Measure on real devices", "Laptops lie", "Phones throttle after a few minutes")
        SessionRepository.addNote(session.id, Note("n1", "Constraints: memory, battery, model size. Ask about thermal throttling.", 2, recId, 51_000))
        SessionRepository.addNote(session.id, Note("m1", MARK_TEXT, 3, recId, 147_000))
        SessionRepository.addNote(session.id, Note("n2", "Look up per-channel calibration", 4, recId, 185_000, spoken = true))
        SessionRepository.addNote(session.id, Note("n3", "Test sustained speed, not peak: 10-minute runs!", 5, recId, 262_000))
        compose.waitForIdle()
        compose.onNodeWithText("Scaling Inference on the Edge").performClick()
        letBackgroundWorkFinish()
    }

    @After
    fun tearDown() {
        PlayerController.release()
    }

    private val talk = listOf(
        TranscriptSegment(500, 12_000, "Thanks, everyone, for coming."),
        TranscriptSegment(12_500, 27_000, "Today I want to talk about running real models on the phone in your pocket."),
        TranscriptSegment(30_000, 45_000, "A few years ago the honest answer was: you mostly couldn't."),
        TranscriptSegment(45_500, 57_000, "Phones didn't have the memory, the battery hated you, and the models were too big."),
        TranscriptSegment(60_000, 80_000, "So what changed? Phone chips grew matrix units."),
        TranscriptSegment(97_000, 115_000, "Quantization is the big one: eight bits, or even four, instead of thirty-two."),
        TranscriptSegment(118_000, 140_000, "Smaller is also faster, because moving numbers through memory costs more than the math."),
        TranscriptSegment(143_000, 160_000, "The catch is calibration: a handful of outlier activations blow up."),
        TranscriptSegment(180_000, 195_000, "Per-channel scales fix most of it."),
        TranscriptSegment(199_000, 220_000, "Benchmarks on a laptop tell you almost nothing about a phone."),
        TranscriptSegment(240_000, 265_000, "Phones throttle: after a couple of minutes many drop to half their clock."),
        TranscriptSegment(300_000, 330_000, "Thank you. I think we have a few minutes for questions."),
    )

    /** A photo of a slide with [lines] on it, taken [atSec] into the talk, and the text read off it. */
    private fun slide(id: String, atSec: Long, vararg lines: String) {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(28, 32, 51))
            val title = Paint().apply { color = android.graphics.Color.WHITE; textSize = 96f; isAntiAlias = true; isFakeBoldText = true }
            val body = Paint().apply { color = android.graphics.Color.rgb(199, 202, 221); textSize = 56f; isAntiAlias = true }
            drawRect(120f, 250f, 260f, 262f, Paint().apply { color = android.graphics.Color.rgb(45, 212, 191) })
            drawText(lines[0], 120f, 380f, title)
            lines.drop(1).forEachIndexed { i, line -> drawText(line, 120f, 500f + i * 90f, body) }
        }
        val file = SessionRepository.photoFile(sessionId, "photo-$id.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        SessionRepository.addNote(sessionId, Note(id, "", 1, recId, atSec * 1000, photo = file.name))
        SessionRepository.setPhotoText(sessionId, id, lines.joinToString("\n"))
    }

    @Test
    fun everythingNotesWithContextAndNotesOnly() {
        compose.onNodeWithText("Everything").assertIsSelected()
        compose.onNodeWithText("Thanks, everyone, for coming.", substring = true).assertIsDisplayed()
        // The title slide was taken 4 s in, during that first paragraph, so it comes right after it.
        compose.onNodeWithText("SLIDE 1 · 00:04").assertIsDisplayed()
        screenshot("13-timeline")

        // Only what was said around each note; the rest folds into "… of talk".
        compose.onNodeWithText("Notes + context").performClick()
        compose.onNodeWithText("Notes + context").assertIsSelected()
        compose.onNodeWithText("Thanks, everyone, for coming.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Phones didn't have the memory", substring = true).assertExists()
        compose.onNodeWithText("29 s of talk").assertIsDisplayed()
        screenshot("14-notes-context")
        // The choice is remembered for next time.
        assertEquals("CONTEXT", Prefs(app).timelineMode)

        compose.onNodeWithText("Notes only").performClick()
        compose.onNodeWithText("Phones didn't have the memory", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Constraints: memory, battery", substring = true).assertExists()

        // A fold opens up the full view at that point.
        compose.onNodeWithText("Notes + context").performClick()
        compose.onNodeWithText("29 s of talk").performClick()
        compose.onNodeWithText("Everything").assertIsSelected()
        compose.onNodeWithText("Today I want to talk about running real models", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingAMarkerPreviewsTheNoteAndPlayIsOneMoreTap() {
        compose.onNodeWithContentDescription("Note at 00:51", substring = true).performClick()
        // The preview says what it is and offers to go there; nothing plays yet.
        compose.onNodeWithText("Play from 00:51").assertIsDisplayed()
        compose.onAllNodesWithText("Constraints: memory, battery, model size. Ask about thermal throttling.").assertCountEquals(2)
        assertTrue(!PlayerController.state.value.isPlaying)
        screenshot("15-peek")

        compose.onNodeWithText("Play from 00:51").performClick()
        compose.waitForIdle()
        assertTrue(PlayerController.state.value.isPlaying)
        // From the usual 3 s lead-in before the note.
        assertEquals(48_000L, PlayerController.state.value.positionMs)
        compose.onNodeWithText("Play from 00:51").assertDoesNotExist()
    }

    @Test
    fun pressingAndSlidingAlongTheMarkersPreviewsEachInTurn() {
        val note = markerCenter("Note at 00:51")
        val mark = markerCenter("Marked moment at 02:27")
        val spoken = markerCenter("Spoken note at 03:05")
        compose.onRoot().performTouchInput {
            down(note)
            advanceEventTime(1_000)
            moveTo(Offset((note.x + mark.x) / 2, note.y))
            moveTo(mark)
        }
        // A ★ has nothing written, so its preview quotes what was being said; the timeline has it in view too.
        compose.onAllNodesWithText("Marked · 02:27").assertCountEquals(2)
        compose.onNodeWithText("“Smaller is also faster", substring = true).assertIsDisplayed()
        compose.onRoot().performTouchInput {
            moveTo(spoken)
            up()
        }
        compose.onAllNodesWithText("Spoken note · 03:05").assertCountEquals(2)
        compose.onNodeWithText("Play from 03:05").assertIsDisplayed()
        screenshot("16-peek-spoken")
        // It goes away by itself after a few seconds.
        compose.mainClock.advanceTimeBy(10_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        compose.waitForIdle()
        compose.onNodeWithText("Play from 03:05").assertDoesNotExist()
    }

    @Test
    fun draggingAlongTheWaveformScrubsAndPlaysFromWhereYouLetGo() {
        val wave = compose.onNodeWithContentDescription("Position in the recording", substring = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(Offset(wave.left + 4f, wave.center.y))
            moveTo(Offset(wave.left + wave.width * 0.3f, wave.center.y))
            moveTo(Offset(wave.left + wave.width * (200f / 372f), wave.center.y))
        }
        // Mid-drag, the slide being passed is opened up in the timeline.
        compose.onNodeWithText("Slide 3 · 03:18".uppercase()).assertIsDisplayed()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        val at = PlayerController.state.value.positionMs
        assertTrue("seeked to $at", at in 197_000L..203_000L)
        screenshot("17-scrubbed")
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkModeKeepsEachKindApart() {
        compose.onNodeWithContentDescription("Spoken note at 03:05", substring = true).performClick()
        compose.onNodeWithText("Play from 03:05").assertIsDisplayed()
        screenshot("18-dark")
    }

    private fun markerCenter(description: String): Offset =
        compose.onNodeWithContentDescription(description, substring = true).fetchSemanticsNode().boundsInRoot.center

    /** Photos and the waveform load off the main thread. */
    private fun letBackgroundWorkFinish() {
        val done = CountDownLatch(1)
        Waveform.afterQueued { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        repeat(10) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun screenshot(name: String) {
        letBackgroundWorkFinish()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
