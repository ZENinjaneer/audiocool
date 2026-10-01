package com.kjwindham.audiocool.transcribe

/**
 * Finds the phrases in a stream of audio from Silero's speech probability for each [window] of it.
 * A phrase starts after [minSpeech] samples above [threshold] and ends after [minSilence] samples
 * below threshold - 0.15, as in sherpa-onnx's own detector, with two additions:
 * - each phrase gets [prePad] and [postPad] samples of context, so first and last words aren't clipped;
 * - no phrase is longer than [maxLength]: one that runs on is cut at its least speech-like window in
 *   the second half. (The built-in limit only makes the detector stricter, and in noisy rooms phrases
 *   ran to 30-50 s, holding up live transcription.)
 * Positions are sample indices from the start of the audio; [onPhrase] gets each phrase's start and end.
 */
class PhraseFinder(
    private val maxLength: Int,
    private val window: Int = 512,
    private val threshold: Float = 0.5f,
    private val minSpeech: Int = 4_000,
    private val minSilence: Int = 8_000,
    private val prePad: Int = 1_600,
    private val postPad: Int = 3_200,
    private val onPhrase: (start: Long, end: Long) -> Unit,
) {
    private val negThreshold = maxOf(threshold - 0.15f, 0.01f)

    /** Samples seen so far. */
    var position = 0L
        private set
    private var speechSince = 0L // end of the window where possible speech began; 0 if none
    private var silenceSince = 0L // end of the window where possible silence began; 0 if none
    private var triggered = false
    private var phraseStart = 0L
    private var lastEnd = 0L

    // Probabilities of the latest windows, enough to look back over a whole phrase.
    private val history = FloatArray(maxLength / window + 2)

    /** Whether the audio so far ends in speech. */
    val inSpeech: Boolean get() = triggered

    /** The earliest sample a phrase found from now on can include; audio before it isn't needed. */
    val keepFrom: Long
        get() = if (triggered) phraseStart else maxOf(lastEnd, position - 2 * window - minSpeech - prePad, 0L)

    fun accept(probability: Float) {
        history[((position / window) % history.size).toInt()] = probability
        position += window
        if (probability > threshold && silenceSince != 0L) silenceSince = 0L
        if (!triggered) {
            if (probability <= threshold) {
                speechSince = 0L
            } else if (speechSince == 0L) {
                speechSince = position
            } else if (position - speechSince >= minSpeech) {
                triggered = true
                phraseStart = maxOf(position - 2 * window - minSpeech - prePad, lastEnd, 0L)
            }
        } else if (probability <= negThreshold) {
            if (silenceSince == 0L) silenceSince = position
            if (position - silenceSince >= minSilence) {
                triggered = false
                speechSince = 0L
                silenceSince = 0L
                emit(phraseStart, minOf(position - minSilence + postPad, position))
                return
            }
        }
        if (triggered && position - phraseStart >= maxLength) cutLongPhrase()
    }

    /** Ends the phrase in progress, if any, at the end of the audio ([end], if given, else [position]). */
    fun finish(end: Long = position) {
        if (triggered) emit(phraseStart, minOf(end, position))
        triggered = false
        speechSince = 0L
        silenceSince = 0L
    }

    private fun cutLongPhrase() {
        // The quietest window between halfway to the limit and the limit itself.
        val first = (phraseStart + maxLength / 2 + window - 1) / window
        val last = (phraseStart + maxLength - window / 2) / window
        var best = last
        for (k in first..last) {
            if (history[(k % history.size).toInt()] < history[(best % history.size).toInt()]) best = k
        }
        val cut = best * window + window / 2
        emit(phraseStart, cut)
        phraseStart = cut
    }

    private fun emit(start: Long, end: Long) {
        lastEnd = end
        if (end > start) onPhrase(start, end)
    }
}
