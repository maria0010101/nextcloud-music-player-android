package com.nextcloud.musicplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VolumeStepManagerTest {

    @Test
    fun testBuildStepTableBoundaryConditions() {
        val systemMax = 15
        val totalSteps = 50

        val table = VolumeStepManager.buildStepTable(totalSteps, systemMax)
        assertEquals(totalSteps + 1, table.size)

        // Step 0: 靜音 (sysLevel = 0, gainOffset = 0dB)
        assertEquals(0, table[0].first)
        assertEquals(0f, table[0].second, 0.001f)

        // Step 1: 最小微步 (sysLevel = 1, 有負增益衰減)
        val step1 = table[1]
        assertEquals(1, step1.first)
        assertTrue("Step 1 增益偏移應小於或等於 0 dB", step1.second <= 0f)

        // Step 50: 最大音量 (sysLevel = 15, gainOffset = 0dB)
        val stepMax = table[50]
        assertEquals(systemMax, stepMax.first)
        assertEquals(0f, stepMax.second, 0.001f)
    }

    @Test
    fun testStepTableMonotonicity() {
        val systemMax = 15
        val totalSteps = 50
        val table = VolumeStepManager.buildStepTable(totalSteps, systemMax)

        for (i in 1 until totalSteps) {
            val (sys1, gain1) = table[i]
            val (sys2, gain2) = table[i + 1]

            // 系統音量單調不減
            assertTrue("System level should be monotonic: $sys1 <= $sys2", sys1 <= sys2)

            // 若在同一系統級距內，微步階負增益應該向 0 dB 遞增（聲音漸漸變大）
            if (sys1 == sys2) {
                assertTrue("Within same sysLevel, gainOffset should increase: $gain1 <= $gain2", gain1 <= gain2)
            }
        }
    }

    @Test
    fun testDifferentStepCounts() {
        val stepCounts = listOf(15, 25, 30, 50, 100)
        val systemMax = 15

        for (steps in stepCounts) {
            val table = VolumeStepManager.buildStepTable(steps, systemMax)
            assertEquals(steps + 1, table.size)
            assertEquals(0, table[0].first)
            assertEquals(systemMax, table[steps].first)
            assertEquals(0f, table[steps].second, 0.001f)
        }
    }

    @Test
    fun testDbToAmplitude() {
        assertEquals(1.0f, VolumeStepManager.dbToAmplitude(0f), 0.001f)
        assertEquals(0.501f, VolumeStepManager.dbToAmplitude(-6f), 0.01f)
        assertEquals(0.1f, VolumeStepManager.dbToAmplitude(-20f), 0.005f)
        assertEquals(0.01f, VolumeStepManager.dbToAmplitude(-40f), 0.001f)
    }

    @Test
    fun testFallbackStepMapping() {
        val (sysLevel0, gain0) = VolumeStepManager.calculateStepMappingFallback(0, 50, 15)
        assertEquals(0, sysLevel0)
        assertEquals(0f, gain0, 0.001f)

        val (sysLevel50, gain50) = VolumeStepManager.calculateStepMappingFallback(50, 50, 15)
        assertEquals(15, sysLevel50)
        assertEquals(0f, gain50, 0.001f)

        val (sysLevel25, gain25) = VolumeStepManager.calculateStepMappingFallback(25, 50, 15)
        assertEquals(8, sysLevel25)
        assertEquals(-1.5f, gain25, 0.001f)
    }
}
