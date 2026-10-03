package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SpeakerTurn
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.Voice
import com.kjwindham.audiocool.speakers.KnownVoices
import com.kjwindham.audiocool.speakers.VoicePrints
import com.kjwindham.audiocool.transcribe.TranscriptionController
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
import java.io.File

/** Who said what in a session: speakers on the timeline, naming one, and asking for it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeakersUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).timelineMode = "EVERYTHING"
        Prefs(app).summaryOfferDismissed = true
        Prefs(app).speechModelOfferDismissed = true
    }

    @After
    fun tearDown() {
        TranscriptionController.cancelAll()
        KnownVoices.forget("Priya")
    }

    @Test
    fun eachSpeakerIsLabelledAndCanBeNamedAndKnownNextTime() {
        val id = meeting(voices = listOf(Voice(0), Voice(1)))
        VoicePrints.set(id, mapOf(0 to floatArrayOf(1f, 0f), 1 to floatArrayOf(0f, 1f)))
        compose.onNodeWithText("Design review").performClick()
        // (At the end of the line under the title, which a long one cuts off.)
        compose.onNodeWithText("2 speakers", substring = true).assertExists()
        compose.onNodeWithText("Speaker 1").assertIsDisplayed()
        compose.onNodeWithText("Speaker 2").assertIsDisplayed()
        // The phrase is cut where the speaker changes.
        compose.onNodeWithText("Let's walk through the sign-up flow.").assertIsDisplayed()
        compose.onNodeWithText("Do we still need the email field?").assertIsDisplayed()
        screenshot("34-who-said-what")

        compose.onNodeWithContentDescription("Speaker 2. Who's this?").performClick()
        compose.onNodeWithText("Who's this?").assertIsDisplayed()
        compose.onNodeWithText("Speaker 2 · 1 turn, under a minute").assertIsDisplayed()
        compose.onNodeWithText("▶ “Do we still need the email field?”").assertIsDisplayed()
        compose.onNodeWithText("Name").performTextReplacement("Priya")
        compose.onNodeWithText("Recognize this voice next time").performClick()
        dialogScreenshot("35-name-a-voice")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("Priya", SessionRepository.get(id)!!.voices.single { it.id == 1 }.name)
        assertTrue(KnownVoices.isKnown("Priya"))
        compose.onNodeWithText("Priya").assertIsDisplayed()
        compose.onAllNodesWithText(" · Who's this?").fetchSemanticsNodes().let { assertEquals(1, it.size) }

        // Switched off again, the voiceprint is forgotten.
        compose.onNodeWithContentDescription("Priya. Rename").performClick()
        compose.onNodeWithText("Recognize this voice next time").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertTrue(!KnownVoices.isKnown("Priya"))
    }

    @Test
    fun theMenuFindsWhoSaidWhatAfterAskingToDownloadTheVoiceModel() {
        val id = meeting(voices = emptyList(), speakers = null)
        compose.onNodeWithText("Design review").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Who said what").performClick()
        compose.onNodeWithText("Find who said what?").assertIsDisplayed()
        compose.onNodeWithText("Download and start").performClick()
        compose.waitForIdle()
        val s = TranscriptionController.state.value
        assertTrue(s.isFindingSpeakers(id))
        assertEquals(listOf("r1"), (listOfNotNull(s.current) + s.queue).filter { it.speakers }.map { it.recId })
    }

    /** A meeting: Sam asks, Priya answers, in one phrase timed word by word. */
    private fun meeting(voices: List<Voice>, speakers: List<SpeakerTurn>? = listOf(SpeakerTurn(0, 3_400, 0), SpeakerTurn(3_400, 8_000, 1))): String {
        val session = SessionRepository.create("Design review")
        val audio = File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        SessionRepository.addRecording(
            session.id,
            Recording(
                "r1", audio.name, 1_000_000, 60_000,
                transcript = listOf(
                    TranscriptSegment(0, 6_000, "Let's walk through the sign-up flow. Do we still need the email field?", listOf(0, 400, 700, 1_000, 1_300, 1_700, 3_500, 3_800, 4_100, 4_400, 4_700, 5_000, 5_400)),
                ),
                transcriptModel = "parakeet-unified-en-0.6b",
                speakers = speakers,
            ),
        )
        if (speakers != null) SessionRepository.setSpeakers(session.id, "r1", speakers, voices)
        compose.waitForIdle()
        return session.id
    }

    /** The dialog showing, which is a window of its own. */
    private fun dialogScreenshot(name: String) {
        compose.waitForIdle()
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
