package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Folders: filing sessions, showing one folder at a time, and searching folders and within one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldersTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        Prefs(app).speechModelOfferDismissed = true
    }

    @Test
    fun sessionsGoInFoldersAndTheMainScreenShowsOneAtATime() {
        val bio = SessionRepository.create("Bio 101")
        val chem = SessionRepository.create("Chem 7")
        compose.waitForIdle()
        // No folders yet, so no folder chips.
        compose.onNodeWithText("All").assertDoesNotExist()

        // From a session's menu, into a new folder.
        compose.onNodeWithText("Bio 101").performTouchInput { longClick() }
        compose.onNodeWithText("Move to folder").performClick()
        compose.onNodeWithText("New folder").performClick()
        typeInDialog("Neuro 214")
        assertEquals("Neuro 214", SessionRepository.get(bio.id)!!.folder)
        // Now there are chips, and in All each session says which folder it's in.
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Neuro 214 · ", substring = true).assertIsDisplayed()

        // A folder's chip shows just what's in it.
        compose.onNodeWithText("Neuro 214").performClick()
        compose.onNodeWithText("Chem 7").assertDoesNotExist()
        compose.onNodeWithText("1 session").assertIsDisplayed()
        // A session started there goes in it.
        compose.onNodeWithContentDescription("New session").performClick()
        compose.waitForIdle()
        assertEquals("Neuro 214", SessionRepository.sessions.value.single { it.id != bio.id && it.id != chem.id }.folder)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("2 sessions").assertIsDisplayed()
        // And the app opens on it next time.
        assertEquals("Neuro 214", Prefs(app).shownFolder)
        screenshot("27-folder")
    }

    @Test
    fun aFolderCanBeRenamedOrDeletedWithoutLosingItsSessions() {
        FolderRepository.create("Neuro 214")
        val bio = SessionRepository.create("Bio 101", folder = "Neuro 214")
        compose.waitForIdle()
        compose.onNodeWithText("Neuro 214").performClick()

        compose.onNodeWithContentDescription("Folder options").performClick()
        compose.onNodeWithText("Rename folder").performClick()
        typeInDialog("Neuroscience 214")
        assertEquals("Neuroscience 214", SessionRepository.get(bio.id)!!.folder)
        compose.onNodeWithText("Neuroscience 214").assertIsDisplayed()
        compose.onNodeWithText("Bio 101").assertIsDisplayed()

        compose.onNodeWithContentDescription("Folder options").performClick()
        compose.onNodeWithText("Delete folder").performClick()
        compose.onNodeWithText("Its session stays, outside any folder.").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertNull(SessionRepository.get(bio.id)!!.folder)
        compose.onNodeWithText("Bio 101").assertIsDisplayed()
        // No folders left, so no chips.
        compose.onNodeWithText("All").assertDoesNotExist()
    }

    @Test
    fun searchFindsFoldersAndKeepsToTheFolderShown() {
        val bio = SessionRepository.create("Bio 101", folder = "Neuro 214")
        SessionRepository.addNote(bio.id, Note("b1", "Neurons fire together", 1))
        val chem = SessionRepository.create("Chem 7")
        SessionRepository.addNote(chem.id, Note("c1", "Neurons in chemistry too", 1))
        compose.waitForIdle()

        // From All: the folder itself, and both sessions' notes.
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("neuro")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("3 results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Folders · 1").assertIsDisplayed()
        // The folder opens.
        compose.onNodeWithText("1 session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bio 101").assertIsDisplayed()
        compose.onNodeWithText("Chem 7").assertDoesNotExist()

        // Searching from there keeps to the folder, until widened.
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("neurons")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("In Neuro 214").assertIsDisplayed()
        compose.onNodeWithText("Neurons in chemistry too").assertDoesNotExist()
        screenshot("28-search-in-folder")
        compose.onNodeWithContentDescription("Search all sessions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Neurons in chemistry too").assertIsDisplayed()
    }

    @Test
    fun aSessionCanBeFiledFromItsOwnMenu() {
        FolderRepository.create("Work")
        val standup = SessionRepository.create("Standup")
        compose.waitForIdle()
        compose.onNodeWithText("Standup").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Move to folder").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.waitForIdle()
        assertEquals("Work", SessionRepository.get(standup.id)!!.folder)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Folder: Work").assertIsDisplayed()
    }

    /** Fills in the name dialog that just opened, and saves. */
    private fun typeInDialog(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
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
