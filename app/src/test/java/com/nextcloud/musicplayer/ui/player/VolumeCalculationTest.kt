package com.nextcloud.musicplayer.ui.player

import org.junit.Assert.assertEquals
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
        val floatVol16 = currentStep.toFloat() / maxSteps.toFloat()
        assertEquals(0.64f, floatVol16, 0.001f)

        // Max boundary
        currentStep = 25
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(25, currentStep)
        assertEquals(1.0f, currentStep.toFloat() / maxSteps.toFloat(), 0.001f)

        // Min boundary
        currentStep = 0
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(0, currentStep)
        assertEquals(0.0f, currentStep.toFloat() / maxSteps.toFloat(), 0.001f)

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
        val floatVol37 = currentStep.toFloat() / maxSteps.toFloat()
        assertEquals(0.74f, floatVol37, 0.001f)

        // Decrement
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(36, currentStep)
        assertEquals(0.72f, currentStep.toFloat() / maxSteps.toFloat(), 0.001f)

        // Max boundary
        currentStep = 50
        currentStep = (currentStep + 1).coerceAtMost(maxSteps)
        assertEquals(50, currentStep)
        assertEquals(1.0f, currentStep.toFloat() / maxSteps.toFloat(), 0.001f)

        // Min boundary
        currentStep = 0
        currentStep = (currentStep - 1).coerceAtLeast(0)
        assertEquals(0, currentStep)
        assertEquals(0.0f, currentStep.toFloat() / maxSteps.toFloat(), 0.001f)

        // Fraction conversion
        val fraction = 0.50f
        val stepFromFraction = (fraction * maxSteps).roundToInt().coerceIn(0, maxSteps)
        assertEquals(25, stepFromFraction)
    }
}
