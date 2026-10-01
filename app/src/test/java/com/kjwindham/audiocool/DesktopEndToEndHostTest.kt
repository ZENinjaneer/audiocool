package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.desktop.PairingInfo
import com.kjwindham.audiocool.transcribe.SpeechModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The phone's desktop sync against a real, running AudioCool Desktop (not the stand-in in
 * DesktopSyncTest). Skipped unless given its address and pairing code:
 * `-PdesktopUrl=http://localhost:8765 -PdesktopToken=<code>`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DesktopEndToEndHostTest {
    private val url = System.getProperty("desktop.url")
    private val token = System.getProperty("desktop.token")

    @After
    fun tearDown() = DesktopSync.unpair()

    @Test
    fun aRecordingSentToTheDesktopComesBackTranscribed() {
        assumeTrue("pass -PdesktopUrl and -PdesktopToken to run against a real desktop", url != null && token != null)
        // JFK's inaugural address (public domain), as the phone records it: AAC in .m4a.
        val clip = File(System.getProperty("desktop.clip")!!)
        val session = SessionRepository.create("JFK")
        clip.copyTo(File(SessionRepository.sessionDir(session.id).apply { mkdirs() }, "recording-1.m4a"))
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", System.currentTimeMillis(), 11_000))

        val pairing = runBlocking { DesktopSync.pair(PairingInfo(url!!, token!!)) }.getOrThrow()
        println("Paired with ${pairing.name} at ${pairing.url}")
        DesktopSync.pollMs = 500
        DesktopSync.send(session.id)
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (DesktopSync.progress.value[session.id]?.phase.let { it != DesktopSync.Phase.DONE && it != DesktopSync.Phase.FAILED }) {
            assertTrue("timed out: ${DesktopSync.progress.value[session.id]}", System.currentTimeMillis() < deadline)
            Thread.sleep(200)
        }
        assertEquals(DesktopSync.progress.value[session.id].toString(), DesktopSync.Phase.DONE, DesktopSync.progress.value[session.id]?.phase)

        val rec = SessionRepository.get(session.id)!!.recordings.single()
        val segments = rec.transcript.orEmpty()
        segments.forEach { println("  [${it.startMs}-${it.endMs} ms] ${it.text}") }
        println("  model: ${rec.transcriptModel}")
        val words = segments.joinToString(" ") { it.text }.lowercase().replace(Regex("[^a-z ]"), "")
        assertTrue(words, "ask not what your country can do for you" in words)
        assertTrue(segments.all { it.startMs in 0 until it.endMs && it.endMs <= 12_000 })
        // Labeled with the desktop's model, so the phone shows where it came from.
        assertNotEquals(SpeechModel.ID, rec.transcriptModel)
    }
}
