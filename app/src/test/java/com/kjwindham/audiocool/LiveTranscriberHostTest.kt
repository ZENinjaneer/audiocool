package com.kjwindham.audiocool

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.transcribe.LiveTranscriber
import com.kjwindham.audiocool.transcribe.PcmChunk
import com.kjwindham.audiocool.transcribe.PcmSource
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.Transcriber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Live transcription with the real engine on the host; skipped without -PsherpaHostDir. */
class LiveTranscriberHostTest {
    private val hostDir = System.getProperty("sherpa.host.dir")?.let(::File)
    private lateinit var recognizer: OfflineRecognizer
    private lateinit var vad: Vad
    private lateinit var clip: FloatArray
    private var rate = 0

    /** Audio arriving bit by bit, like a file that's still being recorded. */
    private class GrowingSource : PcmSource {
        private val queue = LinkedBlockingQueue<PcmChunk>()
        private val end = PcmChunk(FloatArray(0), 0)

        fun push(samples: FloatArray, rate: Int) {
            var i = 0
            while (i < samples.size) {
                val n = minOf(4096, samples.size - i)
                queue.put(PcmChunk(samples.copyOfRange(i, i + n), rate))
                i += n
            }
        }

        fun end() = queue.put(end)

        override fun next(): PcmChunk? = queue.take().takeIf { it !== end }

        override fun close() {}
    }

    @Before
    fun setUp() {
        assumeTrue("pass -PsherpaHostDir to run the speech engine on the host", hostDir != null)
        val model = File(hostDir, "models").listFiles().orEmpty().first { it.name.contains(SpeechModel.ID) }
        recognizer = OfflineRecognizer(null, SpeechModel.recognizerConfig(model, threads = 2))
        vad = Vad(null, Transcriber.vadConfig(File(hostDir, "silero_vad.onnx").path, Transcriber.LIVE_MAX_SEGMENT_SECONDS))
        readWav(File(model, "test_wavs/0.wav")).let { (samples, sampleRate) ->
            clip = samples
            rate = sampleRate
        }
    }

    @After
    fun tearDown() {
        if (::recognizer.isInitialized) recognizer.release()
        if (::vad.isInitialized) vad.release()
    }

    @Test
    fun phrasesArriveWhileTheRecordingIsStillGoing() {
        val source = GrowingSource()
        val arrived = LinkedBlockingQueue<TranscriptSegment>()
        val live = LiveTranscriber(source, recognizer, vad, onSegment = { arrived.put(it) })
        val result = AtomicReference<LiveTranscriber.Result>()
        val worker = thread { result.set(live.run()) }

        source.push(FloatArray(2 * rate), rate)
        source.push(clip, rate)
        source.push(FloatArray(rate), rate) // a one-second pause ends the phrase
        val first = arrived.poll(60, TimeUnit.SECONDS)
        assertNotNull("the phrase should be transcribed before the recording ends", first)
        assertTrue("starts at ${first!!.startMs}", first.startMs in 1_900L..3_000L)
        assertTrue(first.text.isNotBlank())
        assertNull("still running: the recording hasn't ended", result.get())

        source.push(clip, rate)
        source.push(FloatArray(rate), rate)
        source.end()
        worker.join(60_000)
        val done = result.get() as LiveTranscriber.Result.Finished
        assertEquals(first, done.segments[0])
        // Both copies of the clip come out the same (the clip may hold more than one phrase).
        assertTrue(done.segments.size % 2 == 0)
        val half = done.segments.size / 2
        assertEquals(done.segments.take(half).map { it.text }, done.segments.drop(half).map { it.text })
    }

    @Test
    fun stoppingEarlySaysWhereToPickUp() {
        val source = GrowingSource()
        val arrived = LinkedBlockingQueue<TranscriptSegment>()
        val live = LiveTranscriber(source, recognizer, vad, onSegment = { arrived.put(it) })
        val result = AtomicReference<LiveTranscriber.Result>()
        val worker = thread { result.set(live.run()) }

        source.push(FloatArray(2 * rate), rate)
        source.push(clip, rate)
        source.push(FloatArray(rate), rate)
        val first = arrived.poll(60, TimeUnit.SECONDS)!!
        live.stopRequested = true
        source.push(clip, rate) // more audio arrives, but it has stopped
        worker.join(60_000)

        val stopped = result.get() as LiveTranscriber.Result.Stopped
        assertEquals(listOf(first), stopped.segments)
        assertEquals(first.endMs, stopped.resumeFromMs)
        assertNull(stopped.error)
    }

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 0
        var data: FloatArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") sampleRate = bb.getInt(pos + 12)
            if (id == "data") data = FloatArray(size / 2) { bb.getShort(pos + 8 + it * 2) / 32768f }
            pos += 8 + size + (size and 1)
        }
        return requireNotNull(data) to sampleRate
    }
}
