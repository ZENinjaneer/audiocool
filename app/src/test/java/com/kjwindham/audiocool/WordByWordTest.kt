package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TimedWord
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.currentWord
import com.kjwindham.audiocool.data.paragraphs
import com.kjwindham.audiocool.transcribe.Transcriber
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File

/** What was said, word by word: timings from the speech model, the word being said lit up, and playing from a word. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordByWordTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val said = "Sleep comes in cycles of about ninety minutes."

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).timelineMode = "EVERYTHING"
        Prefs(app).summaryOfferDismissed = true
    }

    @After
    fun tearDown() {
        PlayerController.release()
    }

    @Test
    fun theSpeechModelsTokensBecomeWordsWithWhereEachStarts() {
        // As Parakeet gives them: a token starting with a space starts a word; punctuation joins the one before.
        val tokens = arrayOf(" We", "ll", ",", " I", " don", "'", "t", " w", "ish", " cer", "tain", "ly", " ", "very")
        val seconds = floatArrayOf(1.04f, 1.04f, 1.12f, 1.2f, 1.2f, 1.2f, 1.2f, 1.36f, 1.36f, 5.6f, 5.6f, 5.68f, 6.08f, 6.08f)
        val (text, starts) = Transcriber.timedWords(tokens, seconds)!!
        assertEquals("Well, I don't wish certainly very", text)
        assertEquals(listOf(1040, 1200, 1200, 1360, 5600, 6080), starts)
        assertEquals(null, Transcriber.timedWords(emptyArray(), FloatArray(0)))
    }

    @Test
    fun wordTimingsAreKeptAndLaidOverTheParagraph() {
        val rec = recording()
        val back = SessionJson.decode(SessionJson.encode(com.kjwindham.audiocool.data.Session("s", "T", 1, 1, recordings = listOf(rec))))
        assertEquals(rec.transcript, back.recordings.single().transcript)

        val paragraph = paragraphs(rec, emptyList()).single()
        assertEquals(said, paragraph.text)
        assertEquals(8, paragraph.words.size)
        assertEquals(TimedWord(5_000, said.indexOf("ninety"), said.indexOf("ninety") + 6), paragraph.words[6])
        assertEquals(6, currentWord(paragraph.words, 5_100))
        assertEquals(-1, currentWord(paragraph.words, 500))
        // A paragraph with a phrase that wasn't timed isn't lit word by word.
        val mixed = rec.copy(transcript = rec.transcript!!.mapIndexed { i, s -> if (i == 1) s.copy(words = null) else s })
        assertTrue(paragraphs(mixed, emptyList()).single().words.isEmpty())
    }

    @Test
    fun theWordBeingSaidLightsUpAndATappedWordPlaysFromThere() {
        val session = SessionRepository.create("Sleep")
        val audio = File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(60_000, 0))
        SessionRepository.addRecording(session.id, recording().copy(file = audio.name))
        compose.waitForIdle()
        compose.onNodeWithText("Sleep").performClick()
        PlayerController.playFrom(session.id, SessionRepository.get(session.id)!!.recordings.single(), 5_100)
        compose.waitForIdle()

        // The word being said is the one styled apart.
        val text = compose.onNodeWithText(said, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single()
        val lit = text.spanStyles.single { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
        assertEquals("ninety", said.substring(lit.start, lit.end))
        screenshot("31-word-by-word")

        // Tapping a word plays from just before it.
        val node = compose.onNodeWithText(said, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val box = layouts.single().getBoundingBox(said.indexOf("cycles") + 2)
        node.performTouchInput { click(box.center) }
        compose.waitForIdle()
        assertEquals(2_000L, PlayerController.state.value.positionMs)
    }

    @Test
    fun aSessionTranscribedBeforeWordTimingsCanBeTranscribedAgain() {
        val session = SessionRepository.create("Older")
        SessionRepository.addRecording(session.id, recording().copy(transcript = recording().transcript!!.map { it.copy(words = null) }))
        compose.waitForIdle()
        compose.onNodeWithText("Older").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Transcribe again, word by word").performClick()
        compose.waitForIdle()
        // It asks for the speech model first (not downloaded here), then queues the recording.
        compose.onNodeWithText("Download and transcribe").performClick()
        compose.waitForIdle()
        assertTrue(TranscriptionController.state.value.isPending(session.id, "r1"))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        java.io.FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** "Sleep comes in cycles" then "of about ninety minutes.", timed word by word. */
    private fun recording() = Recording(
        "r1", "recording-1.m4a", 1_000_000, 60_000,
        transcript = listOf(
            TranscriptSegment(1_000, 4_000, "Sleep comes in cycles", words = listOf(0, 400, 900, 1_300)),
            TranscriptSegment(4_200, 7_000, "of about ninety minutes.", words = listOf(0, 300, 800, 1_500)),
        ),
        transcriptModel = "parakeet-unified-en-0.6b",
    )
}
