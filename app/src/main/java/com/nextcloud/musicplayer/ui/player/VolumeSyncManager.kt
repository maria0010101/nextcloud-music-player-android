package com.nextcloud.musicplayer.ui.player

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.nextcloud.musicplayer.playback.PlayerController
import kotlin.math.roundToInt

class VolumeSyncManager(
    private val context: Context,
    private val playerController: PlayerController
) {
    private val TAG = "VolumeSyncManager"

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * App 啟動／切入前台：對齊系統音量 (AudioManager -> Step)
     * 1. 讀取當前系統媒體音量 currentSys / maxSys
     * 2. 計算比例並映射為 initialStep
     * 3. 呼叫 ExoPlayer setVolume
     * 4. 將系統媒體音量底座拉滿至 maxSys (flag 0 不彈出原生 UI)
     *
     * @return 映射後的初始步長 initialStep
     */
    fun onEnterForeground(customMaxSteps: Int): Int {
        if (customMaxSteps != 25 && customMaxSteps != 50) {
            playerController.setVolume(1.0f)
            return 15
        }

        return try {
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val initialStep = calculateInitialStep(currentSys, maxSys, customMaxSteps)
            val floatVol = initialStep.toFloat() / customMaxSteps.toFloat()
            playerController.setVolume(floatVol)

            // 將系統媒體音量底座拉滿至最大值（flag 0 不顯示系統原生 UI）
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSys, 0)
            Log.d(TAG, "onEnterForeground: currentSys=$currentSys, maxSys=$maxSys -> initialStep=$initialStep/$customMaxSteps, floatVol=$floatVol, system set to max")
            initialStep
        } catch (e: Exception) {
            Log.e(TAG, "onEnterForeground error", e)
            customMaxSteps
        }
    }

    /**
     * App 退出／退至背景：還原系統音量 (Step -> AudioManager)
     * 1. 讀取 App 內部最後音量比例 appRatio
     * 2. 換算回系統原生級距 restoreSys
     * 3. 呼叫 audioManager.setStreamVolume 還原系統音量 (flag 0 不彈出原生 UI)
     * 4. 將 ExoPlayer 軟體音量設回 1.0f 確保背景串流音量與系統一致
     */
    fun onExitForeground(currentStep: Int, customMaxSteps: Int) {
        if (customMaxSteps != 25 && customMaxSteps != 50) {
            playerController.setVolume(1.0f)
            return
        }

        try {
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val restoreSys = calculateRestoreSystemVolume(currentStep, customMaxSteps, maxSys)

            // 還原系統音量
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreSys, 0)
            // 將播放器軟體音量衰減設回 1.0f，確保背景播放與原生系統音量無縫銜接
            playerController.setVolume(1.0f)
            Log.d(TAG, "onExitForeground: currentStep=$currentStep/$customMaxSteps -> restoreSys=$restoreSys/$maxSys, player set to 1.0f")
        } catch (e: Exception) {
            Log.e(TAG, "onExitForeground error", e)
        }
    }

    companion object {
        fun calculateInitialStep(currentSys: Int, maxSys: Int, customMaxSteps: Int): Int {
            if (maxSys <= 0) return customMaxSteps
            val ratio = (currentSys.toFloat() / maxSys.toFloat()).coerceIn(0f, 1f)
            return (ratio * customMaxSteps).roundToInt().coerceIn(0, customMaxSteps)
        }

        fun calculateRestoreSystemVolume(currentStep: Int, customMaxSteps: Int, maxSys: Int): Int {
            if (customMaxSteps <= 0) return maxSys
            val appRatio = (currentStep.toFloat() / customMaxSteps.toFloat()).coerceIn(0f, 1f)
            return (appRatio * maxSys).roundToInt().coerceIn(0, maxSys)
        }
    }
}
