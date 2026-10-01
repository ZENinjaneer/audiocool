package com.kjwindham.audiocool

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.transcribe.Resampler
import com.kjwindham.audiocool.transcribe.Transcriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real speech engine (sherpa-onnx's Linux build) on the host. Skipped unless the build is
 * given -PsherpaHostDir; see app/build.gradle.kts.
 */
class TranscriberHostTest {
    private val hostDir = System.getProperty("sherpa.host.dir")?.let(::File)

    @Before
    fun needsEngine() {
        assumeTrue("pass -PsherpaHostDir to run the speech engine on the host", hostDir != null)
    }

    @Test
    fun transcribesARecordingWithTimestampsWhereTheSpeechIs() {
        val models = File(hostDir, "models").listFiles().orEmpty().filter { File(it, "encoder_model.ort").exists() }.sorted()
        assumeTrue(models.isNotEmpty())
        for (modelDir in models) {
            val (clip, clipRate) = readWav(File(modelDir, "test_wavs/0.wav"))
            // A phone-style 44.1 kHz recording: silence, speech, a pause, the same speech again, silence.
            val clip44 = Resampler(clipRate, 44_100).let { it.process(clip) + it.flush() }
            val audio = FloatArray(2 * 44_100) + clip44 + FloatArray(3 * 44_100) + clip44 + FloatArray(44_100)
            val audioSeconds = audio.size / 44_100.0

            val recognizer = OfflineRecognizer(null, Transcriber.recognizerConfig(modelDir, threads = 4))
            val vad = Vad(null, Transcriber.vadConfig(File(hostDir, "silero_vad.onnx").path))
            val started = System.nanoTime()
            // Same path as on the phone: 44.1 kHz chunks -> Resampler -> Transcriber.
            val resampler = Resampler(44_100, Transcriber.SAMPLE_RATE)
            val transcriber = Transcriber(recognizer, vad)
            var i = 0
            while (i < audio.size) {
                val n = minOf(4096, audio.size - i)
                transcriber.accept(resampler.process(audio.copyOfRange(i, i + n)))
                i += n
            }
            transcriber.accept(resampler.flush())
            val segments = transcriber.finish()
            val seconds = (System.nanoTime() - started) / 1e9
            recognizer.release()
            vad.release()

            println("${modelDir.name}: %.1f s of audio in %.2f s (%.0fx real time)".format(audioSeconds, seconds, audioSeconds / seconds))
            segments.forEach { println("  [${it.startMs}-${it.endMs} ms] ${it.text}") }

            assertEquals("one segment per spoken clip in ${modelDir.name}", 2, segments.size)
            assertTrue(segments[0].text.isNotBlank())
            // Both clips say the same thing.
            assertEquals(words(segments[0].text), words(segments[1].text))
            // Timestamps land where the speech is: after the 2 s lead-in, and after the 3 s pause.
            val clipMs = clip44.size * 1000L / 44_100
            assertTrue("first starts at ${segments[0].startMs}", segments[0].startMs in 1_900L..2_900L)
            val secondExpected = 2_000 + clipMs + 3_000
            assertTrue("second starts at ${segments[1].startMs}, expected ~$secondExpected", segments[1].startMs in (secondExpected - 100)..(secondExpected + 900))
            assertTrue(segments.all { it.endMs > it.startMs })
        }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z' ]"), " ").split(" ").filter { it.isNotBlank() }

    /** Reads a 16-bit PCM mono WAV into floats. */
    private fun readWav(f: File): Pair<FloatArray, Int> {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var data: FloatArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") rate = bb.getInt(pos + 12)
            if (id == "data") data = FloatArray(size / 2) { bb.getShort(pos + 8 + it * 2) / 32768f }
            pos += 8 + size + (size and 1)
        }
        return requireNotNull(data) to rate
    }
}
