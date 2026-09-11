package com.nextcloud.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadMathTest {

    @Test
    fun testInterpolateToGrid() {
        val targetFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val source = listOf(
            20 to 2.0f,
            100 to 5.0f,
            1000 to -3.0f,
            20000 to 1.0f
        )

        val result = BiquadMath.interpolateToGrid(source, targetFreqs)
        assertEquals(targetFreqs.size, result.size)

        // 1000 Hz 應精確為 -3.0 dB
        val idx1000 = targetFreqs.indexOf(1000)
        assertEquals(-3.0f, result[idx1000], 0.05f)

        // 31 Hz 在 20 Hz (2.0) 與 100 Hz (5.0) 之間，應介於 2.0 與 5.0 之間
        val idx31 = targetFreqs.indexOf(31)
        assertTrue("Result at 31Hz should be between 2.0 and 5.0", result[idx31] in 2.0f..5.0f)
    }

    @Test
    fun testPeakingFilterDesign() {
        val centerFreq = 1000.0
        val gainDb = 6.0
        val q = 1.41

        val coeffs = BiquadMath.designFilter(BiquadMath.TYPE_PEAKING, centerFreq, gainDb, q)

        // 在中心頻率 1000 Hz 處，增益應接近 6.0 dB
        val magAtCenter = BiquadMath.magnitudeDb(coeffs, centerFreq)
        assertEquals(gainDb, magAtCenter, 0.1)

        // 在遠離中心頻率處 (例如 100 Hz)，增益應接近 0 dB
        val magFar = BiquadMath.magnitudeDb(coeffs, 100.0)
        assertEquals(0.0, magFar, 0.6)
    }
}
