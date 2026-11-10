package com.nextcloud.musicplayer.core.settings

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackSettingsTest {
    @Test fun oldAndUnknownModesMigrateToSystemVolume() {
        for (legacy in listOf(null, 15, 30, 1, 60, Int.MAX_VALUE)) {
            val policy = AudioPlaybackSettings.normalize("system", legacy)
            assertEquals(0, policy.volumeSteps)
            assertFalse(policy.usesAppVolume)
            assertEquals(1f, policy.effectiveGain(0.2f), 0f)
        }
    }

    @Test fun bitPerfectRequiresFlickAndNeverUsesSavedGain() {
        val strict = AudioPlaybackSettings.normalize("flick", -1)
        assertTrue(strict.isFlick)
        assertTrue(strict.isBitPerfect)
        assertFalse(strict.usesAppVolume)
        assertEquals(1f, strict.effectiveGain(0f), 0f)
        assertEquals(1f, strict.effectiveGain(0.2f), 0f)
        assertEquals(0, AudioPlaybackSettings.normalize("system", -1).volumeSteps)
        assertEquals(0, AudioPlaybackSettings.normalize("unknown", -1).volumeSteps)
    }

    @Test fun finePrecisionWorksOnBothEngines() {
        for (engine in listOf("system", "flick")) {
            for (steps in listOf(25, 50, 100)) {
                val policy = AudioPlaybackSettings.normalize(engine, steps)
                assertTrue(policy.usesAppVolume)
                assertFalse(policy.isBitPerfect)
                assertEquals(steps, policy.volumeSteps)
                assertEquals(0.1f, policy.effectiveGain(0.1f), 0f)
                assertEquals(0f, policy.effectiveGain(0f), 0f)
            }
        }
    }

    @Test fun exitingStrictToSystemDisablesPrecisionInsteadOfRestoringIt() {
        val strict = AudioPlaybackSettings.normalize("flick", -1)
        val switched = AudioPlaybackSettings.normalize("system", strict.volumeSteps)
        assertEquals(AudioPlaybackSettings("system", 0), switched)
        assertEquals(1f, switched.effectiveGain(0.1f), 0f)
        val fine = AudioPlaybackSettings.normalize("flick", 50)
        assertEquals(50, AudioPlaybackSettings.normalize("system", fine.volumeSteps).volumeSteps)
    }

    @Test fun systemModeDoesNotDestroyThePreviouslyRememberedGain() {
        val rememberedGain = 0.1f
        assertEquals(1f, AudioPlaybackSettings.normalize("flick", 0).effectiveGain(rememberedGain), 0f)
        assertEquals(1f, AudioPlaybackSettings.normalize("flick", -1).effectiveGain(rememberedGain), 0f)
        assertEquals(rememberedGain, AudioPlaybackSettings.normalize("flick", 100).effectiveGain(rememberedGain), 0f)
    }
}
