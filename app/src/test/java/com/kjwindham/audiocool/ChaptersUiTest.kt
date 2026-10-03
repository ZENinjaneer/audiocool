package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.data.ChapterSummary
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.summarize.chapters
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
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

/** A talk without slides in chapters: a heading for each, and the list of them to jump around by. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChaptersUiTest {
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
        PlayerController.release()
    }

    @Test
    fun eachChapterHasAHeadingAndTheListGoesToOne() {
        val session = SessionRepository.create("The Science of Sleep")
        val audio = File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(3_600_000, 0))
        SessionRepository.addRecording(session.id, talk(audio.name))
        // Summaries for its two chapters, as the summary model writes them.
        val chs = chapters(SessionRepository.get(session.id)!!) { null }
        assertEquals(2, chs.size)
        val keys = chs.map { it.key }.toSet()
        SessionRepository.setChapterSummary(session.id, ChapterSummary(chs[0].key, "Sleep comes in cycles, and dreams sort the day's memories.", "a", "test", "Sleep and memory"), keys)
        SessionRepository.setChapterSummary(session.id, ChapterSummary(chs[1].key, "Caffeine lasts hours, so the afternoon cup keeps you up.", "b", "test", "Caffeine and timing"), keys)
        compose.waitForIdle()

        compose.onNodeWithText("The Science of Sleep").performClick()
        compose.onNodeWithText("Chapter 1 · 00:00").assertIsDisplayed()
        compose.onNodeWithText("Sleep and memory").assertIsDisplayed()
        screenshot("37-chapters")

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Chapters").performClick()
        compose.onNodeWithText("2 chapters · 1:00:00").assertIsDisplayed()
        compose.onNodeWithText("Caffeine lasts hours, so the afternoon cup keeps you up.").assertIsDisplayed()
        compose.onNodeWithText("Caffeine and timing").performClick()
        compose.waitForIdle()
        // There, playing from where it starts (a moment early).
        compose.onNodeWithText("Chapter 2 · 05:00").assertIsDisplayed()
        assertEquals(300_000L - 300, PlayerController.state.value.positionMs)
    }

    /** Ten paragraphs about sleep, then ten about caffeine, 60 words each, half a minute apart. */
    private fun talk(file: String): Recording {
        val sleepWords = "sleep cycles dream memory brain night rest deep REM tired".split(' ')
        val caffeineWords = "caffeine coffee espresso half-life tea cup afternoon jitters dose adenosine".split(' ')
        val segments = (0 until 20).map { i ->
            val pool = if (i < 10) sleepWords else caffeineWords
            val text = (0 until 60).joinToString(" ") { j -> if (j % 3 == 0) pool[(i * 7 + j) % pool.size] else listOf("so", "the", "and", "we", "it")[j % 5] }
            TranscriptSegment(i * 30_000L, i * 30_000L + 25_000, text)
        }
        return Recording("r1", file, 1_000_000, 3_600_000, transcript = segments, transcriptModel = "parakeet-unified-en-0.6b")
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
