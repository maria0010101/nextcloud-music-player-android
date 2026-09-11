package com.nextcloud.musicplayer.ui.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.audio.AudioEffectManager
import com.nextcloud.musicplayer.audio.HeadphoneProfile
import com.nextcloud.musicplayer.audio.SoundProfileManager
import com.nextcloud.musicplayer.audio.VolumeStepManager
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
    val volumeStepManager: VolumeStepManager,
    val audioEffectManager: AudioEffectManager,
    val soundProfileManager: SoundProfileManager
) : ViewModel() {

    private val TAG = "PlayerViewModel"

    val volumeSteps: StateFlow<Int> = appSettingsDataStore.volumeSteps
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsDataStore.DEFAULT_VOLUME_STEPS)

    private val _currentStep = MutableStateFlow(AppSettingsDataStore.DEFAULT_VOLUME_STEPS)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _showVolumeHud = MutableStateFlow(false)
    val showVolumeHud: StateFlow<Boolean> = _showVolumeHud.asStateFlow()

    // 彈出音訊等化器與耳機音質設定 Bottom Sheet
    private val _showSoundEffectsSheet = MutableStateFlow(false)
    val showSoundEffectsSheet: StateFlow<Boolean> = _showSoundEffectsSheet.asStateFlow()

    private var hudDismissJob: Job? = null

    init {
        viewModelScope.launch {
            volumeSteps.collect { steps ->
                val step = volumeStepManager.onEnterForeground(steps)
                _currentStep.value = step
                Log.d(TAG, "音量精度步數切換至: $steps 段, 當前步長對齊為: $step")
            }
        }
    }

    /**
     * App 啟動／切入前台：對齊系統音量 (AudioManager -> Step)
     */
    fun onAppForeground() {
        val steps = volumeSteps.value
        val initialStep = volumeStepManager.onEnterForeground(steps)
        _currentStep.value = initialStep
    }

    /**
     * App 退出／退至背景：零爆音狀態維持
     */
    fun onAppBackground() {
        volumeStepManager.onEnterBackground()
    }

    /**
     * 實體音量鍵步長計算與調節 (isIncrement: true 為音量加, false 為音量減)
     */
    fun adjustVolume(isIncrement: Boolean) {
        val maxSteps = volumeSteps.value
        Log.d(TAG, "adjustVolume: isIncrement=$isIncrement, currentStep=${_currentStep.value}, maxSteps=$maxSteps")

        val newStep = if (isIncrement) {
            volumeStepManager.stepUp(maxSteps)
        } else {
            volumeStepManager.stepDown(maxSteps)
        }

        _currentStep.value = newStep
        triggerVolumeHud()
    }

    /**
     * 支援手勢拖曳懸浮條設定音量比例 (0.0f .. 1.0f)
     */
    fun setVolumeFraction(fraction: Float) {
        val maxSteps = volumeSteps.value
        val targetStep = (fraction.coerceIn(0f, 1f) * maxSteps).roundToInt()
        setVolumeStepInternal(targetStep, maxSteps)
        triggerVolumeHud()
    }

    private fun setVolumeStepInternal(step: Int, maxSteps: Int) {
        _currentStep.value = step
        volumeStepManager.setStep(step, maxSteps)
    }

    fun openSoundEffects() {
        _showSoundEffectsSheet.value = true
    }

    fun dismissSoundEffects() {
        _showSoundEffectsSheet.value = false
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
            context: Context,
            playerController: PlayerController,
            appSettingsDataStore: AppSettingsDataStore
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val stepManager = VolumeStepManager(context, playerController)
                val effectManager = AudioEffectManager.getInstance(context)
                val profileManager = SoundProfileManager.getInstance(context)
                return PlayerViewModel(
                    playerController,
                    appSettingsDataStore,
                    stepManager,
                    effectManager,
                    profileManager
                ) as T
            }
        }
    }
}
