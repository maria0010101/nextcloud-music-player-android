package com.nextcloud.musicplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeSyncManagerTest {

    @Test
    fun testCalculateInitialStep() {
        val maxSys = 15

        // 1. System at 0 (Muted)
        assertEquals(0, VolumeSyncManager.calculateInitialStep(0, maxSys, 50))
        assertEquals(0, VolumeSyncManager.calculateInitialStep(0, maxSys, 25))

        // 2. System at Max (15/15 = 100%)
        assertEquals(50, VolumeSyncManager.calculateInitialStep(15, maxSys, 50))
        assertEquals(25, VolumeSyncManager.calculateInitialStep(15, maxSys, 25))

        // 3. System at 60% (9/15)
        assertEquals(30, VolumeSyncManager.calculateInitialStep(9, maxSys, 50))
        assertEquals(15, VolumeSyncManager.calculateInitialStep(9, maxSys, 25))

        // 4. System at 20% (3/15)
        assertEquals(10, VolumeSyncManager.calculateInitialStep(3, maxSys, 50))
        assertEquals(5, VolumeSyncManager.calculateInitialStep(3, maxSys, 25))

        // 5. System at 40% (6/15)
        assertEquals(20, VolumeSyncManager.calculateInitialStep(6, maxSys, 50))
        assertEquals(10, VolumeSyncManager.calculateInitialStep(6, maxSys, 25))
    }

    @Test
    fun testCalculateRestoreSystemVolume() {
        val maxSys = 15

        // 1. App Muted (0/50 -> 0)
        assertEquals(0, VolumeSyncManager.calculateRestoreSystemVolume(0, 50, maxSys))
        assertEquals(0, VolumeSyncManager.calculateRestoreSystemVolume(0, 25, maxSys))

        // 2. App Max (50/50 -> 15)
        assertEquals(15, VolumeSyncManager.calculateRestoreSystemVolume(50, 50, maxSys))
        assertEquals(15, VolumeSyncManager.calculateRestoreSystemVolume(25, 25, maxSys))

        // 3. App at 60% (30/50 -> 9/15)
        assertEquals(9, VolumeSyncManager.calculateRestoreSystemVolume(30, 50, maxSys))
        assertEquals(9, VolumeSyncManager.calculateRestoreSystemVolume(15, 25, maxSys))

        // 4. App at 50% (25/50 -> 7.5 -> 8)
        assertEquals(8, VolumeSyncManager.calculateRestoreSystemVolume(25, 50, maxSys))

        // 5. App at 20% (10/50 -> 3/15)
        assertEquals(3, VolumeSyncManager.calculateRestoreSystemVolume(10, 50, maxSys))
        assertEquals(3, VolumeSyncManager.calculateRestoreSystemVolume(5, 25, maxSys))
    }

    @Test
    fun testRoundTripConsistency() {
        val maxSys = 15
        val customSteps = 50

        // For each system level from 0 to 15, verify round-trip stability
        for (sys in 0..maxSys) {
            val appStep = VolumeSyncManager.calculateInitialStep(sys, maxSys, customSteps)
            val restoredSys = VolumeSyncManager.calculateRestoreSystemVolume(appStep, customSteps, maxSys)
            assertEquals("Round trip should preserve system level for $sys", sys, restoredSys)
        }
    }
}
