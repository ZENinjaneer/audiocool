package com.kjwindham.audiocool

import com.kjwindham.audiocool.transcribe.PhraseFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhraseFinderTest {
    private val phrases = ArrayList<LongRange>()
    private fun finder(maxSeconds: Int = 20) = PhraseFinder(maxLength = maxSeconds * 16_000) { s, e -> phrases += s until e }

    /** Feeds one probability per 512-sample window: [windows] of each (probability, count) run. */
    private fun PhraseFinder.feed(vararg runs: Pair<Float, Int>) {
        for ((p, n) in runs) repeat(n) { accept(p) }
    }

    private fun windows(seconds: Double) = (seconds * 16_000 / 512).toInt()

    @Test
    fun aPhraseKeepsALittleAudioEitherSide() {
        finder().feed(0.1f to windows(1.0), 0.9f to windows(2.0), 0.1f to windows(1.0))
        val speech = windows(1.0) * 512L until (windows(1.0) + windows(2.0)) * 512L
        val phrase = phrases.single()
        // Starts 0.1-0.25 s before the speech and ends 0.2-0.3 s after it.
        assertTrue("$phrase vs $speech", speech.first - phrase.first in 1_600..4_000)
        assertTrue("$phrase vs $speech", phrase.last - speech.last in 3_200..4_800)
    }

    @Test
    fun blipsAndShortPausesDontChangeThePhrases() {
        // A 0.1 s click isn't speech; a 0.3 s pause doesn't end a phrase.
        finder().feed(0.1f to windows(1.0), 0.9f to windows(0.1), 0.1f to windows(1.0), 0.9f to windows(2.0), 0.2f to windows(0.3), 0.9f to windows(2.0), 0.1f to windows(1.0))
        assertEquals(1, phrases.size)
        assertTrue(phrases.single().first > (windows(1.0) + windows(0.1)) * 512L)
    }

    @Test
    fun aLongMonologueIsCutAtItsQuietestMoment() {
        val f = finder(maxSeconds = 12)
        // 30 s of talk with no real pause, but a dip at 19 s (in the second half of the second phrase).
        f.feed(0.1f to windows(1.0), 0.9f to windows(18.0), 0.4f to 1, 0.9f to windows(12.0), 0.1f to windows(1.0))
        assertTrue(phrases.size >= 3)
        for (p in phrases) assertTrue("${p.last - p.first + 1} samples", p.last - p.first + 1 <= 12 * 16_000)
        // The pieces join up exactly: nothing is lost or transcribed twice.
        for (i in 1 until phrases.size) assertEquals(phrases[i - 1].last + 1, phrases[i].first)
        val dip = (windows(1.0) + windows(18.0)) * 512L + 256
        assertTrue("cut at the dip: $phrases", phrases.any { it.last + 1 == dip })
    }

    @Test
    fun finishingMidPhraseKeepsWhatWasSaid() {
        val f = finder()
        f.feed(0.1f to windows(1.0), 0.9f to windows(3.0))
        assertTrue(f.inSpeech)
        f.finish(end = f.position - 100)
        assertEquals(f.position - 100, phrases.single().last + 1)
        assertTrue(!f.inSpeech)
    }

    @Test
    fun audioBeforeKeepFromIsNeverNeeded() {
        val random = Random(7)
        val f = finder(maxSeconds = 8)
        val marks = ArrayList<Pair<Int, Long>>() // phrases found so far, keepFrom
        var speaking = false
        var left = 0
        repeat(20_000) {
            // Runs of speech and silence of random lengths, with noisy probabilities.
            if (left-- == 0) {
                speaking = !speaking
                left = random.nextInt(5, 200)
            }
            f.accept(if (speaking) random.nextFloat() * 0.5f + 0.5f else random.nextFloat() * 0.45f)
            marks += phrases.size to f.keepFrom
        }
        f.finish()
        for ((found, keepFrom) in marks) {
            for (p in phrases.drop(found)) assertTrue("phrase $p starts before $keepFrom", p.first >= keepFrom)
        }
        assertTrue(phrases.size > 50)
    }
}
