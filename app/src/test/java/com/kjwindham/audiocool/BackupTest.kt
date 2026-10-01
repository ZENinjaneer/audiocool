package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.backup.BackupManager
import com.kjwindham.audiocool.backup.FileBackupFolder
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.ui.deleteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun audio(s: Session, rec: Recording) = SessionRepository.audioFile(s.id, rec)

    private fun restore(root: FileBackupFolder) = BackupManager.restore(
        root,
        exists = { SessionRepository.get(it) != null },
        sessionDir = SessionRepository::sessionDir,
        import = { SessionRepository.importSession(it) },
    )

    @Test
    fun backsUpEverythingAndRestoresItAfterAnUninstall() {
        val root = FileBackupFolder(tmp.root)
        val created = SessionRepository.create("Bio 101")
        val dir = SessionRepository.sessionDir(created.id).apply { mkdirs() }
        File(dir, "recording-1.aac").writeBytes(ByteArray(1_000) { it.toByte() })
        SessionRepository.addRecording(created.id, Recording("r1", "recording-1.aac", 1_000L, durationMs = 0))
        SessionRepository.addNote(created.id, Note("n1", "Mitochondria", 2_000L, "r1", 1_500))
        val backupDir = File(tmp.root, BackupManager.folderName(created))

        // While recording, only the notes are backed up; the growing audio file waits until it's finished.
        BackupManager.backUp(root, SessionRepository.get(created.id)!!) { audio(created, it) }
        assertFalse(File(backupDir, "recording-1.aac").exists())
        assertTrue(File(backupDir, "notes.md").readText().contains("Mitochondria"))

        SessionRepository.setRecordingDuration(created.id, "r1", 60_000)
        SessionRepository.setTranscript(created.id, "r1", listOf(TranscriptSegment(2_000, 5_000, "The powerhouse of the cell.")))
        BackupManager.backUp(root, SessionRepository.get(created.id)!!) { audio(created, it) }
        assertEquals(1_000L, File(backupDir, "recording-1.aac").length())
        assertTrue(File(backupDir, "notes.md").readText().contains("[00:02] The powerhouse of the cell."))

        // Converting to .m4a replaces the .aac in the backup too.
        File(dir, "recording-1.m4a").writeBytes(ByteArray(800) { 7 })
        val converted = SessionRepository.get(created.id)!!.let { s ->
            s.copy(recordings = s.recordings.map { it.copy(file = "recording-1.m4a") })
        }
        BackupManager.backUp(root, converted) { audio(created, it) }
        assertFalse(File(backupDir, "recording-1.aac").exists())
        assertEquals(800L, File(backupDir, "recording-1.m4a").length())
        assertEquals(converted, SessionJson.decode(File(backupDir, "session.json").readText()))

        // "Uninstall": the app loses the session and its files.
        deleteSession(created.id)
        SessionRepository.awaitIo()
        assertFalse(dir.exists())

        assertEquals(1, restore(root))
        val back = SessionRepository.get(created.id)!!
        assertEquals(converted.notes, back.notes)
        assertEquals(converted.recordings, back.recordings)
        assertEquals(800L, audio(back, back.recordings.single()).length())
        // Restoring again adds nothing: the session is already there.
        assertEquals(0, restore(root))
    }
}
