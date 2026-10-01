package com.kjwindham.audiocool.transcribe

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.data.TranscriptSegment

/** Mono audio that may still be growing, such as a recording in progress. */
interface PcmSource {
    /** The next chunk, waiting for one if necessary; null once the audio has ended (or the source was stopped). */
    fun next(): PcmChunk?

    /** Frees the source's resources; called on the thread that reads it. */
    fun close()
}

class PcmChunk(val samples: FloatArray, val sampleRate: Int)

/**
 * Transcribes audio while it's being recorded: each stretch of speech is transcribed as soon as it
 * ends and handed to [onSegment]. [run] blocks, so call it on a background thread.
 */
class LiveTranscriber(
    private val source: PcmSource,
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    private val onSegment: (TranscriptSegment) -> Unit,
    private val onSpeaking: (Boolean) -> Unit = {},
    private val denoiser: OnlineSpeechDenoiser? = null,
) {
    sealed interface Result {
        /** Transcribed to the end of the audio. */
        data class Finished(val segments: List<TranscriptSegment>) : Result

        /** Stopped before the end (asked to, or failed); audio from [resumeFromMs] on still needs transcribing. */
        data class Stopped(val segments: List<TranscriptSegment>, val resumeFromMs: Long, val error: Throwable? = null) : Result
    }

    @Volatile
    var stopRequested = false

    fun run(): Result {
        val emitted = ArrayList<TranscriptSegment>()
        fun stopped(error: Throwable? = null) = Result.Stopped(emitted.toList(), emitted.lastOrNull()?.endMs ?: 0L, error)
        try {
            val transcriber = Transcriber(recognizer, vad, maxPhraseSeconds = Transcriber.LIVE_MAX_SEGMENT_SECONDS, denoiser = denoiser, onSegment = {
                emitted += it
                onSegment(it)
            })
            var resampler: Resampler? = null
            var rate = 0
            var speaking = false
            while (!stopRequested) {
                val chunk = source.next() ?: break
                if (resampler == null || chunk.sampleRate != rate) {
                    rate = chunk.sampleRate
                    resampler = Resampler(rate, Transcriber.SAMPLE_RATE)
                }
                transcriber.accept(resampler.process(chunk.samples))
                if (transcriber.isSpeaking != speaking) {
                    speaking = !speaking
                    onSpeaking(speaking)
                }
            }
            if (stopRequested) return stopped()
            resampler?.let { transcriber.accept(it.flush()) }
            return Result.Finished(transcriber.finish())
        } catch (e: Throwable) {
            return stopped(e)
        } finally {
            source.close()
        }
    }
}
