package com.nextcloud.musicplayer.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PlayerViewModel(
    val playerController: PlayerController,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val volumeSyncManager: VolumeSyncManager
) : ViewModel() {

    val volumeSteps: StateFlow<Int> = appSettingsDataStore.volumeSteps
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsDataStore.DEFAULT_VOLUME_STEPS)

    private val _currentStep = MutableStateFlow(AppSettingsDataStore.DEFAULT_VOLUME_STEPS)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _showVolumeHud = MutableStateFlow(false)
    val showVolumeHud: StateFlow<Boolean> = _showVolumeHud.asStateFlow()

    private var hudDismissJob: Job? = null

    init {
        var previousSteps: Int? = null
        viewModelScope.launch {
            volumeSteps.collect { steps ->
                if (previousSteps == null) {
                    previousSteps = steps
                    if (steps == 25 || steps == 50) {
                        _currentStep.value = volumeSyncManager.onEnterForeground(steps)
                    } else {
                        playerController.setVolume(1.0f)
                    }
                } else if (previousSteps != steps) {
                    val oldSteps = previousSteps!!
                    val newStep = volumeSyncManager.onStepsChanged(_currentStep.value, oldSteps, steps)
                    _currentStep.value = newStep
                    previousSteps = steps
                    if (steps != 25 && steps != 50) {
                        _showVolumeHud.value = false
                        hudDismissJob?.cancel()
                    }
                }
            }
        }
    }

    /**
     * App 啟動／切入前台：對齊系統音量 (AudioManager -> Step)
     */
    fun onAppForeground() {
        val steps = volumeSteps.value
        if (steps == 25 || steps == 50) {
            val initialStep = volumeSyncManager.onEnterForeground(steps)
            _currentStep.value = initialStep
        }
    }

    /**
     * App 退出／退至背景：還原系統音量 (Step -> AudioManager)
     */
    fun onAppBackground() {
        val steps = volumeSteps.value
        volumeSyncManager.onExitForeground(_currentStep.value, steps)
    }

    /**
     * 實體音量鍵步長計算與調節 (isIncrement: true 為音量加, false 為音量減)
     */
    fun adjustVolume(isIncrement: Boolean) {
        val maxSteps = volumeSteps.value
        Log.d("PlayerViewModel", "adjustVolume: isIncrement=$isIncrement, currentStep=${_currentStep.value}, maxSteps=$maxSteps")
        if (maxSteps != 25 && maxSteps != 50) return

        val newStep = if (isIncrement) {
            (_currentStep.value + 1).coerceAtMost(maxSteps)
        } else {
            (_currentStep.value - 1).coerceAtLeast(0)
        }

        setVolumeStepInternal(newStep, maxSteps)
        triggerVolumeHud()
    }

    /**
     * 支援手勢拖曳懸浮條設定音量比例 (0.0f .. 1.0f)
     */
    fun setVolumeFraction(fraction: Float) {
        val maxSteps = volumeSteps.value
        Log.d("PlayerViewModel", "setVolumeFraction: fraction=$fraction, maxSteps=$maxSteps")
        if (maxSteps != 25 && maxSteps != 50) return

        val targetStep = (fraction.coerceIn(0f, 1f) * maxSteps).roundToInt()
        setVolumeStepInternal(targetStep, maxSteps)
        triggerVolumeHud()
    }

    private fun setVolumeStepInternal(step: Int, maxSteps: Int) {
        _currentStep.value = step
        val floatVol = VolumeSyncManager.stepToFloatVolume(step, maxSteps)
        Log.d("PlayerViewModel", "setVolumeStepInternal: step=$step/$maxSteps -> floatVol=$floatVol")
        playerController.setVolume(floatVol)
        volumeSyncManager.updateCurrentStep(step, maxSteps)
    }

    /**
     * 顯示音量 HUD 並重設 1.5 秒自動淡出隱藏倒數
     */
    fun triggerVolumeHud() {
        _showVolumeHud.value = true
        hudDismissJob?.cancel()
        hudDismissJob = viewModelScope.launch {
            delay(1500)
            _showVolumeHud.value = false
        }
    }

    fun dismissVolumeHud() {
        hudDismissJob?.cancel()
        _showVolumeHud.value = false
    }

    companion object {
        fun provideFactory(
            playerController: PlayerController,
            appSettingsDataStore: AppSettingsDataStore,
            volumeSyncManager: VolumeSyncManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(playerController, appSettingsDataStore, volumeSyncManager) as T
            }
        }
    }
}
