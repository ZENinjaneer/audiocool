package com.kjwindham.audiocool.transcribe

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate converter using band-limited (windowed-sinc) interpolation. The low-pass
 * cutoff sits just under the lower of the two Nyquist frequencies, so downsampling doesn't alias.
 * Feed audio with [process], then call [flush] once at the end.
 */
class Resampler(inRate: Int, outRate: Int) {
    private val passthrough = inRate == outRate

    // Output n sits at input position n * down / up.
    private val up: Int
    private val down: Int
    private val reach: Int
    private val taps: Int
    private val table: Array<FloatArray> // [phase][tap]

    private var buffer = FloatArray(8192)
    private var bufferLen = 0
    private var bufferStart = 0L // absolute input index of buffer[0]
    private var inputCount = 0L
    private var nextOut = 0L

    init {
        require(inRate > 0 && outRate > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        val cutoff = 0.5 * min(1.0, outRate.toDouble() / inRate) * 0.95 // cycles per input sample
        val halfWidth = ZERO_CROSSINGS / (2 * cutoff) // input samples
        reach = ceil(halfWidth).toInt()
        taps = 2 * reach
        // Tap j of output phase p weighs input base - reach + 1 + j, at distance p/up + reach - 1 - j.
        table = Array(if (passthrough) 0 else up) { p ->
            val row = FloatArray(taps) { j ->
                val d = p.toDouble() / up + reach - 1 - j
                if (kotlin.math.abs(d) >= halfWidth) {
                    0f
                } else {
                    val x = 2 * cutoff * d
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val window = 0.5 * (1 + cos(PI * d / halfWidth))
                    (2 * cutoff * sinc * window).toFloat()
                }
            }
            // Normalize each phase to unity gain so steady signals don't ripple.
            val sum = row.sum()
            if (sum != 0f) for (j in row.indices) row[j] /= sum
            row
        }
    }

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (passthrough) return input.copyOf(count)
        append(input, count)
        inputCount += count
        return produce(limit = Long.MAX_VALUE)
    }

    /** Emits the remaining output, padding the end of the input with silence. */
    fun flush(): FloatArray {
        if (passthrough) return FloatArray(0)
        val total = inputCount * up / down // outputs that fall within the input's duration
        append(FloatArray(reach + 1), reach + 1)
        inputCount += reach + 1
        return produce(limit = total)
    }

    private fun produce(limit: Long): FloatArray {
        val estimate = ((inputCount - bufferStart) * up / down + 2).toInt().coerceAtLeast(0)
        var out = FloatArray(estimate)
        var n = 0
        while (nextOut < limit) {
            val pos = nextOut * down
            val base = pos / up
            if (base + reach >= inputCount) break
            val row = table[(pos % up).toInt()]
            val first = base - reach + 1
            var acc = 0f
            for (j in 0 until taps) {
                val k = first + j
                if (k >= bufferStart) acc += buffer[(k - bufferStart).toInt()] * row[j] // k < 0 only at the start
            }
            if (n == out.size) out = out.copyOf(out.size * 2 + 16)
            out[n++] = acc
            nextOut++
        }
        // Drop input that no later output needs.
        val keepFrom = max(bufferStart, nextOut * down / up - reach + 1)
        val drop = (keepFrom - bufferStart).toInt().coerceAtMost(bufferLen)
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, bufferLen - drop)
            bufferLen -= drop
            bufferStart += drop
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun append(input: FloatArray, count: Int) {
        if (bufferLen + count > buffer.size) buffer = buffer.copyOf(max(buffer.size * 2, bufferLen + count))
        System.arraycopy(input, 0, buffer, bufferLen, count)
        bufferLen += count
    }

    private companion object {
        const val ZERO_CROSSINGS = 8

        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
