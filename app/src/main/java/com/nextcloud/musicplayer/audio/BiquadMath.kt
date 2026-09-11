package com.nextcloud.musicplayer.audio

import kotlin.math.*

/**
 * Biquad IIR filter design and frequency response evaluation.
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook and 32steps DSP math.
 */
object BiquadMath {

    const val TYPE_PEAKING = 0
    const val TYPE_LOW_SHELF = 1
    const val TYPE_HIGH_SHELF = 2
    const val TYPE_LOW_PASS = 3
    const val TYPE_HIGH_PASS = 4

    private const val SAMPLE_RATE = 48000.0

    data class Coeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double
    )

    /**
     * Design a biquad filter using RBJ Audio EQ Cookbook formulas.
     * @param type TYPE_PEAKING, TYPE_LOW_SHELF, or TYPE_HIGH_SHELF
     * @param freq Center/corner frequency in Hz
     * @param gainDb Boost/cut in dB
     * @param q Q factor (bandwidth). Higher = narrower. 0.1 to 10.
     */
    fun designFilter(type: Int, freq: Double, gainDb: Double, q: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0) // sqrt of linear gain
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)

        return when (type) {
            TYPE_PEAKING -> {
                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / a
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                val a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_LOW_PASS -> {
                val b0 = (1.0 - cosW0) / 2.0
                val b1 = 1.0 - cosW0
                val b2 = (1.0 - cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            TYPE_HIGH_PASS -> {
                val b0 = (1.0 + cosW0) / 2.0
                val b1 = -(1.0 + cosW0)
                val b2 = (1.0 + cosW0) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha
                Coeffs(b0, b1, b2, a0, a1, a2)
            }
            else -> Coeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    /**
     * Compute magnitude response in dB at a given frequency.
     */
    fun magnitudeDb(coeffs: Coeffs, freq: Double): Double {
        val w = 2.0 * PI * freq / SAMPLE_RATE
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)

        val num = coeffs.b0 * coeffs.b0 + coeffs.b1 * coeffs.b1 + coeffs.b2 * coeffs.b2 +
                2.0 * (coeffs.b0 * coeffs.b1 + coeffs.b1 * coeffs.b2) * cosW +
                2.0 * coeffs.b0 * coeffs.b2 * cos2W

        val den = coeffs.a0 * coeffs.a0 + coeffs.a1 * coeffs.a1 + coeffs.a2 * coeffs.a2 +
                2.0 * (coeffs.a0 * coeffs.a1 + coeffs.a1 * coeffs.a2) * cosW +
                2.0 * coeffs.a0 * coeffs.a2 * cos2W

        if (den <= 0.0) return 0.0
        return 10.0 * log10(num / den)
    }

    /**
     * Interpolate source frequency/gain pairs onto a target frequency grid.
     * Uses linear interpolation in log-frequency domain.
     */
    fun interpolateToGrid(source: List<Pair<Int, Float>>, targetFreqs: IntArray): FloatArray {
        if (source.isEmpty()) return FloatArray(targetFreqs.size)
        val sorted = source.sortedBy { it.first }
        val result = FloatArray(targetFreqs.size)

        for (i in targetFreqs.indices) {
            val logF = ln(targetFreqs[i].toDouble().coerceAtLeast(1.0))

            val upperIdx = sorted.indexOfFirst { ln(it.first.toDouble().coerceAtLeast(1.0)) >= logF }

            result[i] = when {
                upperIdx <= 0 -> sorted.first().second
                upperIdx >= sorted.size -> sorted.last().second
                else -> {
                    val lo = sorted[upperIdx - 1]
                    val hi = sorted[upperIdx]
                    val logLo = ln(lo.first.toDouble().coerceAtLeast(1.0))
                    val logHi = ln(hi.first.toDouble().coerceAtLeast(1.0))
                    val t = if (logHi != logLo) (logF - logLo) / (logHi - logLo) else 0.5
                    (lo.second + (hi.second - lo.second) * t).toFloat()
                }
            }
        }
        return result
    }
}
