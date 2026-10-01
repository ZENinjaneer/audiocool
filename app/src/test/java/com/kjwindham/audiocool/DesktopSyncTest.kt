package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.desktop.DesktopClient
import com.kjwindham.audiocool.desktop.DesktopException
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.desktop.PairingInfo
import com.kjwindham.audiocool.desktop.parsePairing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DesktopSyncTest {
    private val desktop = FakeDesktop(token = "PAIR1234")

    @After
    fun tearDown() {
        DesktopSync.unpair()
        desktop.close()
    }

    @Test
    fun readsThePairingQrCodeAndTypedAddresses() {
        assertEquals(
            PairingInfo("http://192.168.1.20:8765", "AB12CD34"),
            parsePairing("audiocool://pair?url=http%3A%2F%2F192.168.1.20%3A8765&token=AB12CD34"),
        )
        assertEquals(PairingInfo("http://192.168.1.20:8765", "x"), parsePairing("192.168.1.20", "x"))
        assertEquals(PairingInfo("http://10.0.0.5:9000", "x"), parsePairing("http://10.0.0.5:9000/", " x "))
        assertNull(parsePairing("https://example.com/not-a-pairing"))
        assertNull(parsePairing("192.168.1.20", ""))
    }

    @Test
    fun aWrongPairingCodeIsRejected() {
        try {
            DesktopClient(desktop.url, "WRONG").ping()
            fail("expected a 401")
        } catch (e: DesktopException) {
            assertEquals(401, e.status)
        }
        assertEquals("Test PC", DesktopClient(desktop.url, "PAIR1234").ping().name)
    }

    @Test
    fun sendsTheSessionAndBringsBackTheDesktopTranscript() {
        val session = SessionRepository.create("Bio 101")
        val dir = SessionRepository.sessionDir(session.id).apply { mkdirs() }
        File(dir, "recording-1.m4a").writeBytes(ByteArray(5_000) { it.toByte() })
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", 1_000L, 60_000))
        SessionRepository.setTranscript(session.id, "r1", listOf(TranscriptSegment(0, 900, "phone version")), "parakeet-unified-en-0.6b")

        val pairing = runBlocking { DesktopSync.pair(PairingInfo(desktop.url, "PAIR1234")) }.getOrThrow()
        assertEquals("Test PC", pairing.name)
        DesktopSync.pollMs = 20
        DesktopSync.send(session.id)
        waitFor { DesktopSync.progress.value[session.id]?.phase == DesktopSync.Phase.DONE }

        // The audio went over, and only the finished recording was asked for.
        assertEquals(5_000, desktop.files["${session.id}/recording-1.m4a"]?.size)
        assertEquals(1, desktop.transcribeRequests.size)
        // The desktop's transcript replaced the phone's, labeled with the desktop's model.
        val rec = SessionRepository.get(session.id)!!.recordings.single()
        assertEquals(listOf(TranscriptSegment(1_000, 4_000, "Transcribed by the desktop.")), rec.transcript)
        assertEquals("big-model", rec.transcriptModel)

        // Sending again doesn't re-upload or re-transcribe.
        DesktopSync.send(session.id)
        waitFor { desktop.sessions.isNotEmpty() && DesktopSync.progress.value[session.id]?.phase == DesktopSync.Phase.DONE }
        Thread.sleep(200)
        assertEquals(1, desktop.transcribeRequests.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out; progress=${DesktopSync.progress.value}", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }
}
