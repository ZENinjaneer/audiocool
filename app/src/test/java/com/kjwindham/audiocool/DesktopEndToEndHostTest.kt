package com.kjwindham.audiocool

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.desktop.DesktopSummaries
import com.kjwindham.audiocool.desktop.DesktopSync
import com.kjwindham.audiocool.desktop.PairingInfo
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.util.Prefs
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's desktop sync against a real, running AudioCool Desktop (not the stand-in in
 * DesktopSyncTest). Skipped unless given its address and pairing code:
 * `-PdesktopUrl=http://localhost:8765 -PdesktopToken=<code>`. The summaries test needs the desktop's
 * summary model downloaded.
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

    @Test
    fun theDesktopsModelWritesTheSummariesAndAnswers() {
        assumeTrue("pass -PdesktopUrl and -PdesktopToken to run against a real desktop", url != null && token != null)
        runBlocking { DesktopSync.pair(PairingInfo(url!!, token!!)) }.getOrThrow()
        val model = DesktopSummaries.check(force = true)
        assumeTrue("the desktop's summary model isn't downloaded", model != null)
        println("Summaries on ${model!!.desktop}: ${model.name}")
        Prefs(ApplicationProvider.getApplicationContext()).summaries = true

        // A lecture with two slides, summarized by the phone's own controller, through the desktop.
        val session = SessionRepository.create("Lecture")
        val said = lecture.mapIndexed { i, text -> TranscriptSegment(i * 20_000L, i * 20_000L + 18_000, text) }
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", System.currentTimeMillis(), lecture.size * 20_000L, said, "parakeet-unified-en-0.6b"))
        SessionRepository.addNote(session.id, Note("s1", "", 1, "r1", 0, photo = "photo-s1.jpg", photoText = "Why we sleep\nAdenosine and sleep pressure"))
        SessionRepository.addNote(session.id, Note("s2", "", 2, "r1", 200_000, photo = "photo-s2.jpg", photoText = "Sleep and memory\nThe hippocampus at night"))
        val started = System.currentTimeMillis()
        val deadline = started + 10 * 60_000
        while (SessionRepository.get(session.id)!!.summary == null) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            Thread.sleep(200)
        }
        val done = SessionRepository.get(session.id)!!
        println("Summarized in ${(System.currentTimeMillis() - started) / 1000} s:")
        done.chapterSummaries.forEach { println("  [${it.key}] ${it.title}: ${it.text}  (${it.model})") }
        println("  Session: ${done.summary!!.title} — ${done.summary!!.text}")
        done.summary!!.keyPoints.forEach { println("    • $it") }
        done.summary!!.actionItems.forEach { println("    ☐ $it") }
        assertEquals(2, done.chapterSummaries.size)
        assertTrue(done.chapterSummaries.all { it.model == "${model.id}@desktop" && it.text.isNotBlank() && !it.title.isNullOrBlank() })
        assertEquals("${model.id}@desktop", done.summary!!.model)
        assertTrue(done.summary!!.keyPoints.isNotEmpty())

        // A question, answered there too, with the moments it came from.
        SummaryController.ask(session.id, "What does caffeine do to adenosine?")
        while (SummaryController.answer.value?.thinking != false) {
            assertTrue("timed out", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            Thread.sleep(100)
        }
        val answer = SummaryController.answer.value!!
        println("  Q: ${answer.question}\n  A: ${answer.text} ${answer.moments.map { it.atMs / 1000 }}")
        assertTrue(answer.text.orEmpty().contains("recept", ignoreCase = true) || answer.text.orEmpty().contains("block", ignoreCase = true))
        assertTrue(answer.moments.isNotEmpty())
        SummaryController.clearAnswer()
    }

    private val lecture = listOf(
        "Good morning, everyone. Today is all about sleep, and why your brain needs it.",
        "We spend about a third of our lives asleep, and for a long time scientists assumed the brain simply switched off at night.",
        "It turns out the sleeping brain is incredibly busy. It sorts memories, clears out waste, and resets the body for the next day.",
        "So why do we get sleepy at all? A big part of the answer is a molecule called adenosine.",
        "Adenosine builds up in your brain the whole time you're awake. The longer you're up, the more there is, and the stronger the pressure to sleep.",
        "Caffeine works by blocking adenosine receptors. It doesn't remove the pressure; it just hides it for a while.",
        "That's why the crash comes when the caffeine wears off: all that adenosine was still there, waiting.",
        "Caffeine has a half-life of five to six hours, so an afternoon coffee is still working at bedtime.",
        "Sleep itself comes in cycles of about ninety minutes, from light sleep into deep sleep and up into REM.",
        "Deep sleep fills the early night, and REM takes over toward morning, which is why you often wake from a dream.",
        "Let's talk about memory, because this is where sleep gets really interesting.",
        "During deep sleep, the hippocampus replays what you learned during the day and hands it to the cortex for long-term storage.",
        "In rats you can watch it happen: the neurons that fired while running a maze fire again, in the same order, while they sleep.",
        "REM seems to do something different. It links new memories with old ones, which may be why sleep helps with creative problems.",
        "Students who sleep after studying remember far more the next day than students who stay up, with the same study time.",
        "So pulling an all-nighter before an exam is close to the worst possible strategy. For next week, read chapter four.",
    )
}
