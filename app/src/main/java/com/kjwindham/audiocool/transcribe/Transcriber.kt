package com.kjwindham.audiocool.transcribe

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.kjwindham.audiocool.data.TranscriptSegment
import kotlin.math.min

/**
 * Turns 16 kHz mono audio into timestamped text. Silero VAD scores each 32 ms for speech,
 * [PhraseFinder] groups that into phrases (capped in length, so a long monologue still gets frequent
 * timestamps to jump to), and Parakeet transcribes each phrase as soon as it ends, passing it to
 * [onSegment]. Feed audio with [accept], then call [finish].
 *
 * With a [denoiser], speech is looked for in a denoised copy, which finds far more of it in quiet,
 * echoey or noisy rooms (a lecture hall test went from 16% of words wrong to 6%). Parakeet still
 * hears the original: it got worse on denoised audio in every test.
 */
class Transcriber(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    /** Added to every timestamp, for audio that starts partway into a recording. */
    private val offsetMs: Long = 0,
    maxPhraseSeconds: Float = MAX_SEGMENT_SECONDS,
    /** If given, speech is looked for in a denoised copy; the recognizer still hears the original. */
    private val denoiser: OnlineSpeechDenoiser? = null,
    /** Bring each phrase to a standard loudness before recognizing it. */
    private val levelSegments: Boolean = false,
    private val onSegment: (TranscriptSegment) -> Unit = {},
) {
    private val window = FloatArray(VAD_WINDOW)
    private var windowFill = 0
    private val segments = mutableListOf<TranscriptSegment>()
    private val phrases = PhraseFinder(maxLength = (maxPhraseSeconds * SAMPLE_RATE).toInt(), window = VAD_WINDOW, onPhrase = ::transcribe)

    // The audio a phrase may still need: audio[i] is sample audioStart + i, up to audioEnd.
    private var audio = FloatArray(SAMPLE_RATE * 30)
    private var audioStart = 0L
    private var audioEnd = 0L

    fun accept(samples: FloatArray, count: Int = samples.size) {
        store(samples, count)
        if (denoiser != null) {
            val denoised = denoiser.run(if (count == samples.size) samples else samples.copyOf(count), SAMPLE_RATE).samples
            detect(denoised, denoised.size)
        } else {
            detect(samples, count)
        }
    }

    /** Whether the audio fed so far ends in speech. */
    val isSpeaking: Boolean get() = phrases.inSpeech

    /** Transcribes whatever speech is still buffered and returns every segment, in order. */
    fun finish(): List<TranscriptSegment> {
        denoiser?.flush()?.samples?.let { detect(it, it.size) }
        if (windowFill > 0) {
            window.fill(0f, windowFill, VAD_WINDOW)
            phrases.accept(vad.compute(window))
            windowFill = 0
        }
        phrases.finish(end = audioEnd)
        return segments.toList()
    }

    private fun detect(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = min(VAD_WINDOW - windowFill, count - i)
            System.arraycopy(samples, i, window, windowFill, n)
            windowFill += n
            i += n
            if (windowFill == VAD_WINDOW) {
                windowFill = 0
                phrases.accept(vad.compute(window))
            }
        }
    }

    private fun store(samples: FloatArray, count: Int) {
        var used = (audioEnd - audioStart).toInt()
        if (used + count > audio.size) {
            // Drop what no phrase will need, and grow if what's left is still more than half full.
            val keepFrom = phrases.keepFrom.coerceIn(audioStart, audioEnd)
            val drop = (keepFrom - audioStart).toInt()
            used -= drop
            val target = if (used + count > audio.size / 2) FloatArray(maxOf(audio.size * 2, used + count)) else audio
            System.arraycopy(audio, drop, target, 0, used)
            audio = target
            audioStart = keepFrom
        }
        System.arraycopy(samples, 0, audio, used, count)
        audioEnd += count
    }

    private fun transcribe(start: Long, end: Long) {
        val from = maxOf(start, audioStart)
        val to = minOf(end, audioEnd)
        if (to <= from) return
        val text = recognize(audio.copyOfRange((from - audioStart).toInt(), (to - audioStart).toInt()))
        if (text.isEmpty()) return
        val segment = TranscriptSegment(offsetMs + from * 1000 / SAMPLE_RATE, offsetMs + to * 1000 / SAMPLE_RATE, text)
        segments += segment
        onSegment(segment)
    }

    private fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(if (levelSegments) level(samples) else samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Scales a phrase to about -24 dBFS RMS (at most +18 dB), without clipping. */
        fun level(samples: FloatArray): FloatArray {
            if (samples.isEmpty()) return samples
            var power = 0.0
            var peak = 0f
            for (v in samples) {
                power += v * v
                peak = maxOf(peak, kotlin.math.abs(v))
            }
            val rms = kotlin.math.sqrt(power / samples.size).toFloat()
            if (rms < 1e-5f) return samples
            val gain = minOf(0.06f / rms, 8f, 0.99f / peak.coerceAtLeast(1e-5f))
            return FloatArray(samples.size) { samples[it] * gain }
        }
        const val VAD_WINDOW = 512
        const val MAX_SEGMENT_SECONDS = 20f

        /** Live transcription caps phrases shorter, so lines keep appearing during long stretches of talk. */
        const val LIVE_MAX_SEGMENT_SECONDS = 12f

        /** GTCRN; [model] is a file path, or an asset path when the denoiser is created with an AssetManager. */
        fun denoiserConfig(model: String) = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model), numThreads = 1),
        )

        /**
         * [model] is a file path, or an asset path when the Vad is created with an AssetManager. Only
         * the model's speech probabilities are used ([Vad.compute]); [PhraseFinder] does the rest.
         */
        fun vadConfig(model: String) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = model, windowSize = VAD_WINDOW),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
    }
}
