package com.kjwindham.audiocool.transcribe

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.kjwindham.audiocool.data.TranscriptSegment
import java.io.File
import kotlin.math.min

/**
 * Turns 16 kHz mono audio into timestamped text. Silero VAD finds the stretches of speech (capped
 * at [MAX_SEGMENT_SECONDS], so a long monologue still gets frequent timestamps to jump to) and
 * Moonshine transcribes each one. Feed audio with [accept], then call [finish].
 */
class Transcriber(private val recognizer: OfflineRecognizer, private val vad: Vad) {
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
                val start = segment.start.toLong() * 1000 / SAMPLE_RATE
                val end = (segment.start.toLong() + segment.samples.size) * 1000 / SAMPLE_RATE
                segments += TranscriptSegment(start, end, text)
            }
        }
    }

    private fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val VAD_WINDOW = 512
        const val MAX_SEGMENT_SECONDS = 20f

        /** [model] is a file path, or an asset path when the Vad is created with an AssetManager. */
        fun vadConfig(model: String) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = MAX_SEGMENT_SECONDS,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )

        /** Moonshine v2: an encoder plus a merged decoder. */
        fun recognizerConfig(modelDir: File, threads: Int) = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = File(modelDir, "encoder_model.ort").path,
                    mergedDecoder = File(modelDir, "decoder_model_merged.ort").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = threads,
            ),
        )
    }
}
