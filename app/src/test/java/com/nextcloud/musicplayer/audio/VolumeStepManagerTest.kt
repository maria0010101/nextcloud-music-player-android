package com.nextcloud.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeStepManagerTest {
    @Test
    fun internalGainHasRealMuteAndFullScale() {
        assertEquals(0f, PlaybackVolume.fractionToGain(0f), 0f)
        assertEquals(1f, PlaybackVolume.fractionToGain(1f), 0.0001f)
    }

    @Test
    fun internalGainIsMonotonicAtEverySupportedStepCount() {
        for (steps in listOf(15, 25, 30, 50, 100)) {
            var previous = -1f
            for (step in 0..steps) {
                val gain = PlaybackVolume.fractionToGain(step.toFloat() / steps)
                assertTrue("gain decreased at $step/$steps", gain > previous)
                previous = gain
            }
        }
    }

    @Test
    fun halfScaleIsIndependentOfDeviceStepCount() {
        assertEquals(
            PlaybackVolume.fractionToGain(0.5f),
            PlaybackVolume.fractionToGain(25f / 50f),
            0f
        )
        assertEquals(0.1f, PlaybackVolume.fractionToGain(0.5f), 0.001f)
    }
}
