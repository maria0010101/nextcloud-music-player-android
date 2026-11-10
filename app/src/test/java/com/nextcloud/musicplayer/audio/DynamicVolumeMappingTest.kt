package com.nextcloud.musicplayer.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

class DynamicVolumeMappingTest {
    @Test fun actualDbCurveIsInterpolatedWithoutAmplifyingFullScaleSamples() {
        for (max in listOf(15, 25, 30)) {
            for (steps in listOf(25, 50, 100)) {
                var previousDb = Float.NEGATIVE_INFINITY
                for (step in 1..steps) {
                    val fraction = step.toFloat() / steps
                    fun db(i: Int) = -60f * (1f - i.toFloat() / max) * (1f - i.toFloat() / max)
                    val result = DynamicVolumeMapping.map(fraction, max, ::db)
                    assertTrue(result.systemStep in 1..max)
                    assertTrue(result.gain > 0f && result.gain <= 1f)
                    val x = fraction * max
                    assertTrue(result.systemStep - x < 1.00001f)
                    val low = x.toInt()
                    val high = kotlin.math.ceil(x.toDouble()).toInt()
                    val target = db(low) + (db(high) - db(low)) * (x - low)
                    val combined = db(result.systemStep) + 20f * log10(result.gain)
                    assertEquals(target, combined, 0.0001f)
                    assertTrue(combined >= previousDb)
                    previousDb = combined
                }
            }
        }
    }
    @Test fun muteFullScaleAndUnsupportedDbAreFinite() {
        assertEquals(DynamicVolumeMapping.Result(0, 0f), DynamicVolumeMapping.map(0f, 15) { -10f })
        assertEquals(DynamicVolumeMapping.Result(15, 1f), DynamicVolumeMapping.map(1f, 15) { -60f + it * 4 })
        for (db in listOf(Float.NaN, Float.NEGATIVE_INFINITY)) {
            val result = DynamicVolumeMapping.map(0.01f, 15) { db }
            assertTrue(result.gain.isFinite() && result.gain in 0f..1f)
        }
    }
}
