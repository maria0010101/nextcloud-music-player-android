package com.nextcloud.musicplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class VolumeCalculationTest {

    @Test
    fun test25StepsCalculation() {
        val maxSteps = 25
        var currentStep = 15

        // Increment
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(16, currentStep)
        val floatVol16 = VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps)
        val targetDb16 = VolumeSyncManager.stepToDb(currentStep, maxSteps)
        assertEquals(-21.0f, targetDb16, 0.1f)
        assertTrue(floatVol16 in 0.08f..0.10f)

        // Max boundary
        currentStep = 25
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(25, currentStep)
        assertEquals(1.0f, VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps), 0.001f)

        // Min boundary
        currentStep = 0
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(0, currentStep)
        assertEquals(0.0f, VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps), 0.001f)

        // Fraction conversion
        val fraction = 0.72f
        val stepFromFraction = (fraction * maxSteps).roundToInt().coerceIn(0, maxSteps)
        assertEquals(18, stepFromFraction)
    }

    @Test
    fun test50StepsCalculation() {
        val maxSteps = 50
        var currentStep = 36

        // Increment
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(37, currentStep)
        val floatVol37 = VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps)
        val targetDb37 = VolumeSyncManager.stepToDb(currentStep, maxSteps)
        assertEquals(-14.85f, targetDb37, 0.1f)
        assertTrue(floatVol37 in 0.17f..0.19f)

        // Decrement
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(36, currentStep)
        val floatVol36 = VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps)
        val targetDb36 = VolumeSyncManager.stepToDb(currentStep, maxSteps)
        assertEquals(-16.0f, targetDb36, 0.1f)
        assertTrue(floatVol36 in 0.15f..0.17f)

        // Max boundary
        currentStep = 50
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(50, currentStep)
        assertEquals(1.0f, VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps), 0.001f)

        // Min boundary
        currentStep = 0
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(0, currentStep)
        assertEquals(0.0f, VolumeSyncManager.stepToFloatVolume(currentStep, maxSteps), 0.001f)

        // Fraction conversion
        val fraction = 0.50f
        val stepFromFraction = (fraction * maxSteps).roundToInt().coerceIn(0, maxSteps)
        assertEquals(25, stepFromFraction)
    }
}
