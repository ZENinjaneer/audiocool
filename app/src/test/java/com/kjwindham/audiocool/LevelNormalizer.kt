package com.kjwindham.audiocool

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Benchmark experiment (didn't help Parakeet, so the app doesn't use it): slowly evens out loudness so a quiet, far-away speaker reaches the speech detector and the model
 * at a usable level. Gain changes are smoothed over blocks, rise quickly and fall slowly (like a
 * compressor's attack and release), and are capped so background noise in silence isn't pumped up.
 */
class LevelNormalizer(
    sampleRate: Int,
    private val targetRms: Float = 0.06f, // about -24 dBFS
    private val maxGain: Float = 8f, // +18 dB
    private val minGain: Float = 0.5f,
) {
    private val block = sampleRate / 50 // 20 ms
    private val attack = exp(-1.0 / 5).toFloat() // envelope rises over ~100 ms
    private val release = exp(-1.0 / 150).toFloat() // and falls over ~3 s
    private var envelope = targetRms * targetRms // mean power
    private var gain = 1f
    private val pending = FloatArray(block)
    private var fill = 0

    fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        var o = 0
        for (x in input) {
            pending[fill++] = x
            if (fill == block) {
                o = flushBlock(out, o)
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    /** Whatever is still buffered (less than one block). */
    fun flush(): FloatArray {
        if (fill == 0) return FloatArray(0)
        val out = FloatArray(fill)
        for (i in 0 until fill) out[i] = (pending[i] * gain).coerceIn(-1f, 1f)
        fill = 0
        return out
    }

    private fun flushBlock(out: FloatArray, start: Int): Int {
        var power = 0f
        for (v in pending) power += v * v
        power /= block
        val coeff = if (power > envelope) attack else release
        envelope = coeff * envelope + (1 - coeff) * power
        val target = (targetRms / sqrt(envelope + 1e-10f)).coerceIn(minGain, maxGain)
        // Ramp from the previous block's gain to avoid clicks.
        val from = gain
        for (i in 0 until block) {
            val g = from + (target - from) * (i + 1) / block
            out[start + i] = (pending[i] * g).coerceIn(-1f, 1f)
        }
        gain = target
        fill = 0
        return start + block
    }
}
