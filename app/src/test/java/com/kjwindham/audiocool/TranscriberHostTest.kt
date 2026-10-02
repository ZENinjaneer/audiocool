package com.kjwindham.audiocool

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.transcribe.Resampler
import com.kjwindham.audiocool.transcribe.SpeechModel
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
        val models = File(hostDir, "models").listFiles().orEmpty().filter { it.name.contains(SpeechModel.ID) }
        assumeTrue(models.isNotEmpty())
        for (modelDir in models) {
            val (clip, clipRate) = readWav(File(modelDir, "test_wavs/0.wav"))
            // A phone-style 44.1 kHz recording: silence, speech, a pause, the same speech again, silence.
            val clip44 = Resampler(clipRate, 44_100).let { it.process(clip) + it.flush() }
            val audio = FloatArray(2 * 44_100) + clip44 + FloatArray(3 * 44_100) + clip44 + FloatArray(44_100)
            val audioSeconds = audio.size / 44_100.0

            val recognizer = OfflineRecognizer(null, SpeechModel.recognizerConfig(modelDir, threads = 4))
            val vad = Vad(null, Transcriber.vadConfig(File(hostDir, "silero_vad.onnx").path))
            val denoiser = OnlineSpeechDenoiser(null, Transcriber.denoiserConfig(File(hostDir, "gtcrn_simple.onnx").path))
            val started = System.nanoTime()
            // Same path as on the phone: 44.1 kHz chunks -> Resampler -> Transcriber.
            val resampler = Resampler(44_100, Transcriber.SAMPLE_RATE)
            val transcriber = Transcriber(recognizer, vad, denoiser = denoiser)
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
            denoiser.release()

            println("${modelDir.name}: %.1f s of audio in %.2f s (%.0fx real time)".format(audioSeconds, seconds, audioSeconds / seconds))
            segments.forEach { println("  [${it.startMs}-${it.endMs} ms] ${it.text}") }

            // The clip may hold more than one phrase; either way both copies come out the same.
            assertTrue("an even number of phrases in ${modelDir.name}: $segments", segments.isNotEmpty() && segments.size % 2 == 0)
            val (first, second) = segments.take(segments.size / 2) to segments.drop(segments.size / 2)
            assertEquals(words(first.joinToString(" ") { it.text }), words(second.joinToString(" ") { it.text }))
            // Timestamps land where the speech is: after the 2 s lead-in, and one clip plus the 3 s pause later.
            val clipMs = clip44.size * 1000L / 44_100
            assertTrue("first starts at ${first[0].startMs}", first[0].startMs in 1_900L..3_000L)
            val gap = second[0].startMs - first[0].startMs
            assertTrue("second copy starts $gap ms after the first, expected ~${clipMs + 3_000}", kotlin.math.abs(gap - (clipMs + 3_000)) <= 150)
            assertTrue(segments.all { it.endMs > it.startMs })
            // Timed word by word: a start for every word, in order, within the phrase.
            for (s in segments) {
                val starts = s.words
                assertTrue("word times for \"${s.text}\": $starts", starts != null && starts.size == s.text.split(' ').size)
                assertTrue("in order: $starts", starts!!.zipWithNext().all { (a, b) -> a <= b } && starts.last() <= s.endMs - s.startMs)
            }
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
