package com.kjwindham.audiocool

import com.kjwindham.audiocool.transcribe.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(hz: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Streams [input] through in uneven chunks, the way decoded audio arrives. */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val r = Resampler(from, to)
        val out = ArrayList<Float>()
        var i = 0
        var chunk = 1000
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + n)).forEach { out += it }
            i += n
            chunk = if (chunk == 1000) 4096 else 1000
        }
        r.flush().forEach { out += it }
        return out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))

    @Test
    fun keepsSpeechBandTonesIntactWhenDownsampling() {
        val out = resample(tone(440.0, 44_100, 2.0), 44_100, 16_000)
        assertEquals(32_000, out.size)
        // Away from the edges, the output is the same tone sampled at 16 kHz.
        val worst = (2_000 until 30_000).maxOf { abs(out[it] - (0.5 * sin(2 * PI * 440 * it / 16_000)).toFloat()) }
        assertTrue("max error $worst", worst < 0.01)
    }

    @Test
    fun filtersOutWhatWouldAlias() {
        // 12 kHz can't be represented at 16 kHz; it must be removed rather than fold down to 4 kHz.
        val out = resample(tone(12_000.0, 44_100, 1.0), 44_100, 16_000)
        val level = rms(out, 1_000, 15_000)
        assertTrue("aliased energy $level", level < 0.01)
    }

    @Test
    fun handlesOtherRatesAndPassthrough() {
        assertEquals(16_000, resample(tone(300.0, 48_000, 1.0), 48_000, 16_000).size)
        assertEquals(16_000, resample(tone(300.0, 24_000, 1.0), 24_000, 16_000).size)
        assertEquals(32_000, resample(tone(300.0, 8_000, 2.0), 8_000, 16_000).size)
        val same = tone(300.0, 16_000, 1.0)
        assertTrue(same.contentEquals(resample(same, 16_000, 16_000)))
    }
}
