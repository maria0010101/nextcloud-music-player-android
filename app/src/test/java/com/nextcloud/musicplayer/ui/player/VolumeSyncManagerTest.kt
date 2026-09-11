package com.nextcloud.musicplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VolumeSyncManagerTest {

    @Test
    fun testDecibelValuesAndStep1MinimumAudible() {
        // Step 0: 靜音
        assertEquals(Float.NEGATIVE_INFINITY, VolumeSyncManager.stepToDb(0, 25))
        assertEquals(Float.NEGATIVE_INFINITY, VolumeSyncManager.stepToDb(0, 50))
        assertEquals(0.0f, VolumeSyncManager.stepToFloatVolume(0, 25), 0.00001f)
        assertEquals(0.0f, VolumeSyncManager.stepToFloatVolume(0, 50), 0.00001f)

        // Step 1: 最小可聞分貝 (-56.0 dB)，振幅約 0.00158 (極低微音，不再是暴衝的 0.04 或 0.02)
        val dbStep1_25 = VolumeSyncManager.stepToDb(1, 25)
        val dbStep1_50 = VolumeSyncManager.stepToDb(1, 50)
        assertEquals(VolumeSyncManager.MIN_AUDIBLE_DB, dbStep1_25, 0.001f)
        assertEquals(VolumeSyncManager.MIN_AUDIBLE_DB, dbStep1_50, 0.001f)

        val ampStep1_25 = VolumeSyncManager.stepToFloatVolume(1, 25)
        val ampStep1_50 = VolumeSyncManager.stepToFloatVolume(1, 50)
        assertEquals(0.001585f, ampStep1_25, 0.0001f)
        assertEquals(0.001585f, ampStep1_50, 0.0001f)

        // Step Max: 最大音量 (0.0 dB, 振幅 1.0)
        assertEquals(0.0f, VolumeSyncManager.stepToDb(25, 25), 0.001f)
        assertEquals(0.0f, VolumeSyncManager.stepToDb(50, 50), 0.001f)
        assertEquals(1.0f, VolumeSyncManager.stepToFloatVolume(25, 25), 0.00001f)
        assertEquals(1.0f, VolumeSyncManager.stepToFloatVolume(50, 50), 0.00001f)

        // 單調遞增驗證
        for (step in 1 until 50) {
            val v1 = VolumeSyncManager.stepToFloatVolume(step, 50)
            val v2 = VolumeSyncManager.stepToFloatVolume(step + 1, 50)
            assertTrue("Step $step ($v1) should be less than step ${step + 1} ($v2)", v1 < v2)
        }
    }

    @Test
    fun testCalculateScaledStep() {
        // 1. Min boundary (0 -> 0)
        assertEquals(0, VolumeSyncManager.calculateScaledStep(0, 25, 50))
        assertEquals(0, VolumeSyncManager.calculateScaledStep(0, 50, 25))

        // 2. Step 1 (最小微音) 兩者皆為 -56.0 dB，應精確對齊為 step 1
        assertEquals(1, VolumeSyncManager.calculateScaledStep(1, 25, 50))
        assertEquals(1, VolumeSyncManager.calculateScaledStep(1, 50, 25))

        // 3. Max boundary (100% -> 100%)
        assertEquals(50, VolumeSyncManager.calculateScaledStep(25, 25, 50))
        assertEquals(25, VolumeSyncManager.calculateScaledStep(50, 50, 25))

        // 4. 25 段與 50 段互切時，體感音量分貝差異應小於 1.0 dB
        for (step in 1..25) {
            val scaled50 = VolumeSyncManager.calculateScaledStep(step, 25, 50)
            val db25 = VolumeSyncManager.stepToDb(step, 25)
            val db50 = VolumeSyncManager.stepToDb(scaled50, 50)
            val diff = abs(db25 - db50)
            assertTrue("Step $step/25 -> $scaled50/50 dB diff $diff should be < 1.0 dB", diff < 1.0f)
        }
    }

    @Test
    fun testRoundTripConsistency15Steps() {
        val maxSys = 15
        val customSteps = 50

        // 原生 15 段設備下，每一級系統音量進出 App 應完全還原相同級距
        for (sys in 0..maxSys) {
            val appStep = VolumeSyncManager.calculateInitialStep(sys, maxSys, customSteps)
            val restoredSys = VolumeSyncManager.calculateRestoreSystemVolume(appStep, customSteps, maxSys)
            assertEquals("Round trip should preserve system level for $sys", sys, restoredSys)
        }
    }

    @Test
    fun testXiaomi150StepsConsistency() {
        val maxSys = 150
        val customSteps = 50

        // 小米 HyperOS 150 級距：驗證 15 級實體按鍵階梯 (0, 10, 20, ..., 150) 進出 App 體感一致
        for (click in 0..15) {
            val sys = click * 10
            val appStep = VolumeSyncManager.calculateInitialStep(sys, maxSys, customSteps)
            val restoredSys = VolumeSyncManager.calculateRestoreSystemVolume(appStep, customSteps, maxSys)
            // 驗證還原之級距與原級距在 150 級中差距 <= 2（分貝差異小於 0.5 dB）
            assertTrue("Click $click ($sys): restored $restoredSys should be close to $sys", abs(sys - restoredSys) <= 2)
        }
    }
}
