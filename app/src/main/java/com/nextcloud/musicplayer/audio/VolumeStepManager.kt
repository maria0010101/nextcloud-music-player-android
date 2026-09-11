package com.nextcloud.musicplayer.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.nextcloud.musicplayer.playback.PlayerController
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

class VolumeStepManager(
    private val context: Context,
    private val playerController: PlayerController
) {
    private val TAG = "VolumeStepManager"

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioEffectManager: AudioEffectManager by lazy {
        AudioEffectManager.getInstance(context)
    }

    val systemMaxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var currentStep: Int = 15
        private set

    var currentTotalSteps: Int = 15
        private set

    // Step table: stepTable[step] = Pair(sysLevel, gainOffsetDb)
    private var stepTable: Array<Pair<Int, Float>> = emptyArray()

    /**
     * App 啟動或切入前台：從系統音量讀取並對齊至內部步長，絕不擅自將系統音量拉至最大值
     */
    fun onEnterForeground(totalSteps: Int): Int {
        currentTotalSteps = totalSteps
        ensureStepTable(totalSteps)
        val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = mapSystemVolumeToStep(sysVol, totalSteps)
        currentStep = step
        val (_, gainDb) = getStepMapping(step, totalSteps)
        audioEffectManager.setSoftwareGainOffsetDb(gainDb)
        Log.d(TAG, "onEnterForeground: sysVol=$sysVol/$systemMaxVolume -> step=$step/$totalSteps, gainDb=$gainDb dB")
        return step
    }

    /**
     * App 退至背景：不需還原任何底座，因為系統音量自始至終維持在真實值
     */
    fun onEnterBackground() {
        Log.d(TAG, "onEnterBackground: 維持真實系統音量，零爆音切換")
    }

    /**
     * 設定步長 (0 .. totalSteps)
     */
    fun setStep(step: Int, totalSteps: Int) {
        val clampedStep = step.coerceIn(0, totalSteps)
        currentStep = clampedStep
        currentTotalSteps = totalSteps
        ensureStepTable(totalSteps)

        val (targetSysVol, gainOffsetDb) = getStepMapping(clampedStep, totalSteps)
        val currentSysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // 僅在跨越系統級距時才調整系統音量，級距內部僅變更微步階 DSP 衰減
        if (targetSysVol != currentSysVol) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
            } catch (e: Exception) {
                Log.w(TAG, "setStreamVolume 失敗", e)
            }
        }

        // 套用微步階 DSP 衰減
        audioEffectManager.setSoftwareGainOffsetDb(gainOffsetDb)

        // ExoPlayer 振幅安全聯動
        val ampFactor = if (clampedStep == 0) 0f else dbToAmplitude(gainOffsetDb)
        playerController.setVolume(ampFactor)

        Log.d(TAG, "setStep: $clampedStep/$totalSteps -> sysVol=$targetSysVol, gainDb=$gainOffsetDb dB, amp=$ampFactor")
    }

    fun stepUp(totalSteps: Int): Int {
        val next = (currentStep + 1).coerceAtMost(totalSteps)
        setStep(next, totalSteps)
        return next
    }

    fun stepDown(totalSteps: Int): Int {
        val prev = (currentStep - 1).coerceAtLeast(0)
        setStep(prev, totalSteps)
        return prev
    }

    private fun ensureStepTable(totalSteps: Int) {
        if (stepTable.size != totalSteps + 1) {
            stepTable = buildStepTable(totalSteps, systemMaxVolume)
        }
    }

    private fun getStepMapping(step: Int, totalSteps: Int): Pair<Int, Float> {
        ensureStepTable(totalSteps)
        return if (step in stepTable.indices) {
            stepTable[step]
        } else {
            calculateStepMappingFallback(step, totalSteps, systemMaxVolume)
        }
    }

    private fun mapSystemVolumeToStep(sysVol: Int, totalSteps: Int): Int {
        ensureStepTable(totalSteps)
        if (sysVol <= 0) return 0
        var bestStep = 1
        for (i in 1 until stepTable.size) {
            if (stepTable[i].first <= sysVol) {
                bestStep = i
            }
        }
        return bestStep.coerceIn(0, totalSteps)
    }

    companion object {
        const val LEVEL_STEP_DB = 3.0f // Android 系統每級音量間隔約 3.0 dB

        /**
         * 32steps 黃金演算法：將總步長映射至 (sysLevel, gainOffsetDb)
         */
        fun buildStepTable(totalSteps: Int, systemMax: Int): Array<Pair<Int, Float>> {
            if (totalSteps <= 0 || systemMax <= 0) {
                return Array(1) { Pair(0, 0f) }
            }

            val table = Array(totalSteps + 1) { Pair(0, 0f) }
            table[0] = Pair(0, 0f) // 靜音

            for (step in 1..totalSteps) {
                val fraction = step.toFloat() / totalSteps.toFloat()
                val floatSysVol = (fraction * systemMax).coerceIn(0.001f, systemMax.toFloat())
                val sysLevel = ceil(floatSysVol).toInt().coerceIn(1, systemMax)
                val attenuation = sysLevel - floatSysVol
                val gainOffsetDb = -(attenuation * LEVEL_STEP_DB)
                table[step] = Pair(sysLevel, gainOffsetDb)
            }
            return table
        }

        fun calculateStepMappingFallback(step: Int, totalSteps: Int, systemMax: Int): Pair<Int, Float> {
            if (step <= 0) return Pair(0, 0f)
            val fraction = step.toFloat() / totalSteps.coerceAtLeast(1)
            val floatSysVol = (fraction * systemMax).coerceIn(0.001f, systemMax.toFloat())
            val sysLevel = ceil(floatSysVol).toInt().coerceIn(1, systemMax)
            val attenuation = sysLevel - floatSysVol
            val gainOffsetDb = -(attenuation * LEVEL_STEP_DB)
            return Pair(sysLevel, gainOffsetDb)
        }

        fun dbToAmplitude(db: Float): Float {
            return 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)
        }

        fun stepToFloatVolume(step: Int, maxSteps: Int): Float {
            if (step <= 0) return 0f
            if (step >= maxSteps) return 1f
            val fraction = step.toFloat() / maxSteps.toFloat()
            // Logarithmic perception curve
            return 10.0.pow((-40.0 * (1.0 - fraction)) / 20.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
