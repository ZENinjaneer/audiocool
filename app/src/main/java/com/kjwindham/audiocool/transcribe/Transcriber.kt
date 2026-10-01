package com.kjwindham.audiocool.transcribe

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.kjwindham.audiocool.data.TranscriptSegment
import kotlin.math.min

/**
 * Turns 16 kHz mono audio into timestamped text. Silero VAD finds the stretches of speech (capped in
 * length, so a long monologue still gets frequent timestamps to jump to) and Moonshine transcribes
 * each one as soon as it ends, passing it to [onSegment]. Feed audio with [accept], then call [finish].
 */
class Transcriber(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    /** Added to every timestamp, for audio that starts partway into a recording. */
    private val offsetMs: Long = 0,
    /** Bring each phrase to a standard loudness before recognizing it. */
    private val levelSegments: Boolean = false,
    private val onSegment: (TranscriptSegment) -> Unit = {},
) {
    private val window = FloatArray(VAD_WINDOW)
    private var windowFill = 0
    private val segments = mutableListOf<TranscriptSegment>()

    fun accept(samples: FloatArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = min(VAD_WINDOW - windowFill, count - i)
            System.arraycopy(samples, i, window, windowFill, n)
            windowFill += n
            i += n
            if (windowFill == VAD_WINDOW) {
                vad.acceptWaveform(window.copyOf())
                windowFill = 0
                drain()
            }
        }
    }

    /** Whether the audio fed so far ends in speech. */
    val isSpeaking: Boolean get() = vad.isSpeechDetected()

    /** Transcribes whatever speech is still buffered and returns every segment, in order. */
    fun finish(): List<TranscriptSegment> {
        if (windowFill > 0) {
            vad.acceptWaveform(window.copyOf(windowFill))
            windowFill = 0
        }
        vad.flush()
        drain()
        return segments.toList()
    }

    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val text = recognize(segment.samples)
            if (text.isNotEmpty()) {
                val start = offsetMs + segment.start.toLong() * 1000 / SAMPLE_RATE
                val end = offsetMs + (segment.start.toLong() + segment.samples.size) * 1000 / SAMPLE_RATE
                val transcribed = TranscriptSegment(start, end, text)
                segments += transcribed
                onSegment(transcribed)
            }
        }
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

        /** [model] is a file path, or an asset path when the Vad is created with an AssetManager. */
        fun vadConfig(model: String, maxSegmentSeconds: Float = MAX_SEGMENT_SECONDS) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = maxSegmentSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
    }
}
