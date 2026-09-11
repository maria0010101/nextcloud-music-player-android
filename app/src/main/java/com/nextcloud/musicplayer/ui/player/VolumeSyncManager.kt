package com.nextcloud.musicplayer.ui.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class VolumeSyncManager(
    private val context: Context,
    private val playerController: PlayerController
) {
    private val TAG = "VolumeSyncManager"

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val prefs by lazy {
        context.getSharedPreferences("volume_sync_prefs", Context.MODE_PRIVATE)
    }

    /**
     * 記錄系統底座目前是否已被拉滿至 maxSys
     */
    var isSystemMaxed: Boolean = false
        private set

    var lastKnownStep: Int = 25
        private set

    var lastKnownMaxSteps: Int = 50
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var maxSystemJob: Job? = null

    init {
        // 異常退出防護：若上次執行時系統底座拉滿且未正常還原（例如 Process 被系統或使用者強行終止）
        // 則在此處及時還原，避免重開 App 或在系統環境下系統音量永久卡死在最大值
        try {
            val wasMaxed = prefs.getBoolean(KEY_SYSTEM_MAXED, false)
            val savedSysVol = prefs.getInt(KEY_SAVED_SYS_VOL, -1)
            lastKnownStep = prefs.getInt(KEY_SAVED_STEP, 25)
            lastKnownMaxSteps = prefs.getInt(KEY_SAVED_MAX_STEPS, 50)

            if (wasMaxed && savedSysVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedSysVol, 0)
                Log.w(TAG, "偵測到上次未正常退出，已自動還原系統媒體音量至: $savedSysVol")
                prefs.edit().putBoolean(KEY_SYSTEM_MAXED, false).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "VolumeSyncManager 初始化異常退出檢查失敗", e)
        }
    }

    /**
     * App 啟動／切入前台：對齊系統音量 (AudioManager -> Step)
     * 1. 讀取當前系統媒體音量 currentSys / maxSys
     * 2. 以真實 dB 映射為 initialStep
     * 3. 先下發 ExoPlayer 軟體衰減至目標振幅
     * 4. 延遲 150ms 待音訊緩衝區排空後，再將系統媒體音量底座拉滿至 maxSys，杜絕切換瞬間爆音！
     *
     * @return 映射後的初始步長 initialStep
     */
    fun onEnterForeground(customMaxSteps: Int): Int {
        if (customMaxSteps != 25 && customMaxSteps != 50) {
            maxSystemJob?.cancel()
            maxSystemJob = null
            if (isSystemMaxed) {
                val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val restoreSys = calculateRestoreSystemVolume(lastKnownStep, lastKnownMaxSteps, maxSys, audioManager)
                restoreSystemVolumeInternal(restoreSys)
            }
            playerController.setVolume(1.0f)
            return 15
        }

        // 若系統底座已拉滿（例如重複進入前台或段數設定監聽觸發），直接以 lastKnownStep 計算，嚴禁重複讀取 maxSys 造成爆音！
        if (isSystemMaxed) {
            val step = if (lastKnownMaxSteps == customMaxSteps) {
                lastKnownStep
            } else {
                calculateScaledStep(lastKnownStep, lastKnownMaxSteps, customMaxSteps)
            }
            lastKnownStep = step
            lastKnownMaxSteps = customMaxSteps
            val floatVol = stepToFloatVolume(step, customMaxSteps)
            playerController.setVolume(floatVol)
            Log.d(TAG, "onEnterForeground (已拉滿狀態維持): step=$step/$customMaxSteps, floatVol=$floatVol")
            return step
        }

        return try {
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val deviceType = getActiveOutputDeviceType(audioManager)
            val initialStep = calculateInitialStep(currentSys, maxSys, customMaxSteps, audioManager, deviceType)
            val floatVol = stepToFloatVolume(initialStep, customMaxSteps)
            lastKnownStep = initialStep
            lastKnownMaxSteps = customMaxSteps

            // 儲存原始系統音量，供異常終止時自動還原
            prefs.edit()
                .putBoolean(KEY_SYSTEM_MAXED, true)
                .putInt(KEY_SAVED_SYS_VOL, currentSys)
                .putInt(KEY_SAVED_STEP, initialStep)
                .putInt(KEY_SAVED_MAX_STEPS, customMaxSteps)
                .apply()

            if (currentSys >= maxSys) {
                // 原生系統底座本來就在最大值，直接設定軟體音量
                isSystemMaxed = true
                playerController.setVolume(floatVol)
            } else {
                // 核心防爆音防線：
                // 1. 先下發 ExoPlayer 軟體衰減至 floatVol（並確保 MediaController 連線就緒）
                // 2. 嚴禁立即呼叫 setStreamVolume(maxSys)，否則硬體瞬間拉滿至 0dB 時，
                //    AudioTrack 緩衝區內殘留之 1.0f 振幅 PCM 音訊將引發巨大爆音！
                // 3. 待 150ms 讓軟體衰減確實穿透 IPC 並填滿 AudioTrack 硬體緩衝區後，
                //    再將系統媒體音量底座拉滿至 maxSys，徹底杜絕切換瞬間的爆音！
                maxSystemJob?.cancel()
                maxSystemJob = scope.launch {
                    playerController.connect { controller ->
                        controller.volume = floatVol
                    }
                    playerController.setVolume(floatVol)

                    delay(150)

                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSys, 0)
                    isSystemMaxed = true
                    Log.d(TAG, "onEnterForeground: 延遲安全拉滿系統底座完成 (currentSys=$currentSys -> maxSys=$maxSys)")
                }
            }

            Log.d(TAG, "onEnterForeground: currentSys=$currentSys, maxSys=$maxSys -> initialStep=$initialStep/$customMaxSteps, floatVol=$floatVol")
            initialStep
        } catch (e: Exception) {
            Log.e(TAG, "onEnterForeground error", e)
            customMaxSteps
        }
    }

    /**
     * App 退出／退至背景：還原系統音量 (Step -> AudioManager)
     * 1. 取消任何進行中的拉滿任務
     * 2. 讀取 App 內部最後音量步長與目標 dB
     * 3. 換算回系統原生最相近 dB 之級距 restoreSys
     * 4. 呼叫 audioManager.setStreamVolume 還原系統音量 (flag 0 不彈出原生 UI)
     * 5. 將 ExoPlayer 軟體音量設回 1.0f 確保背景串流音量與系統一致
     */
    fun onExitForeground(currentStep: Int, customMaxSteps: Int) {
        maxSystemJob?.cancel()
        maxSystemJob = null

        if (!isSystemMaxed) {
            playerController.setVolume(1.0f)
            return
        }

        try {
            val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val deviceType = getActiveOutputDeviceType(audioManager)
            val restoreSys = calculateRestoreSystemVolume(currentStep, customMaxSteps, maxSys, audioManager, deviceType)
            restoreSystemVolumeInternal(restoreSys)
            Log.d(TAG, "onExitForeground: currentStep=$currentStep/$customMaxSteps -> restoreSys=$restoreSys/$maxSys, restored & player set to 1.0f")
        } catch (e: Exception) {
            Log.e(TAG, "onExitForeground error", e)
        }
    }

    /**
     * 在設定畫面中動態切換音量段數（例如 15 <-> 25 <-> 50）
     * 平滑按分貝維持體感音量重新縮放步長，避免重讀已拉滿的系統音量導致持續維持在 100% 高音量
     */
    fun onStepsChanged(oldStep: Int, oldMaxSteps: Int, newMaxSteps: Int): Int {
        maxSystemJob?.cancel()
        maxSystemJob = null

        if (newMaxSteps != 25 && newMaxSteps != 50) {
            // 切換回預設 15 段（系統原生模式）：還原系統音量底座並將軟體衰減設回 1.0f
            onExitForeground(oldStep, oldMaxSteps)
            return 15
        }

        if (oldMaxSteps != 25 && oldMaxSteps != 50) {
            // 從 15 段切換至 25/50 段：切入自訂前台模式
            return onEnterForeground(newMaxSteps)
        }

        // 25 段與 50 段之間相互切換：底座已經在 maxSys，保持相同 dB 體感音量平滑縮放
        val newStep = calculateScaledStep(oldStep, oldMaxSteps, newMaxSteps)
        val floatVol = stepToFloatVolume(newStep, newMaxSteps)
        lastKnownStep = newStep
        lastKnownMaxSteps = newMaxSteps
        playerController.setVolume(floatVol)

        prefs.edit()
            .putInt(KEY_SAVED_STEP, newStep)
            .putInt(KEY_SAVED_MAX_STEPS, newMaxSteps)
            .apply()

        Log.d(TAG, "onStepsChanged: $oldStep/$oldMaxSteps -> $newStep/$newMaxSteps, floatVol=$floatVol (維持 dB 音量)")
        return newStep
    }

    /**
     * 當使用者按鍵或拖曳調節音量時同步更新最新步長快取
     */
    fun updateCurrentStep(currentStep: Int, customMaxSteps: Int) {
        if (customMaxSteps > 0) {
            lastKnownStep = currentStep
            lastKnownMaxSteps = customMaxSteps
            prefs.edit()
                .putInt(KEY_SAVED_STEP, currentStep)
                .putInt(KEY_SAVED_MAX_STEPS, customMaxSteps)
                .apply()

            if (!isSystemMaxed && (customMaxSteps == 25 || customMaxSteps == 50)) {
                maxSystemJob?.cancel()
                maxSystemJob = null
                val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSys, 0)
                isSystemMaxed = true
            }
        }
    }

    private fun restoreSystemVolumeInternal(restoreSys: Int) {
        val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clampedSys = restoreSys.coerceIn(0, maxSys)

        // 還原系統音量
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clampedSys, 0)
        // 將播放器軟體音量衰減設回 1.0f，確保背景播放與原生系統音量無縫銜接
        playerController.setVolume(1.0f)

        isSystemMaxed = false
        prefs.edit()
            .putBoolean(KEY_SYSTEM_MAXED, false)
            .putInt(KEY_SAVED_SYS_VOL, clampedSys)
            .apply()
    }

    companion object {
        const val KEY_SYSTEM_MAXED = "key_system_volume_was_maxed"
        const val KEY_SAVED_SYS_VOL = "key_saved_system_volume"
        const val KEY_SAVED_STEP = "key_saved_step"
        const val KEY_SAVED_MAX_STEPS = "key_saved_max_steps"

        /**
         * 最小可聞音量分貝基準 (-56.0 dB，對應約 0.00158 線性振幅)
         * 完美對齊 Android 原生系統第 1 級階梯（實測約 -54.1 dB ~ -58 dB），
         * 徹底解決 step 1 音量過大無法微音輸出的問題。
         */
        const val MIN_AUDIBLE_DB = -56.0f
        const val MAX_DB = 0.0f

        /**
         * 取得當前活躍的音訊輸出設備類型 (耳機、藍牙或內建喇叭)
         */
        fun getActiveOutputDeviceType(audioManager: AudioManager?): Int {
            if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    for (device in devices) {
                        when (device.type) {
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                            AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            AudioDeviceInfo.TYPE_USB_HEADSET -> return device.type
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to speaker
                }
            }
            return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        /**
         * 將自訂步長 (1..customMaxSteps) 轉換為目標 dB 衰減值
         * step == 0 -> -Infinity dB (靜音)
         * step == 1 -> MIN_AUDIBLE_DB (-56.0 dB, 輕柔微音)
         * step == customMaxSteps -> MAX_DB (0.0 dB, 最大不衰減)
         */
        fun stepToDb(step: Int, customMaxSteps: Int): Float {
            if (step <= 0) return Float.NEGATIVE_INFINITY
            if (step >= customMaxSteps) return MAX_DB
            val fraction = (step - 1).toFloat() / (customMaxSteps - 1).toFloat()
            return MIN_AUDIBLE_DB + (MAX_DB - MIN_AUDIBLE_DB) * fraction
        }

        /**
         * 將 dB 衰減值轉換為 ExoPlayer 的線性振幅 (0.0f .. 1.0f)
         * linearAmplitude = 10^(dB / 20)
         */
        fun dbToFloatVolume(db: Float): Float {
            if (db.isInfinite() || db <= -100.0f) return 0.0f
            if (db >= 0.0f) return 1.0f
            return Math.pow(10.0, (db / 20.0).toDouble()).toFloat().coerceIn(0.0f, 1.0f)
        }

        /**
         * 將自訂步長 (0..customMaxSteps) 轉換為 ExoPlayer 軟體音量振幅
         */
        fun stepToFloatVolume(step: Int, customMaxSteps: Int): Float {
            if (step <= 0) return 0.0f
            if (step >= customMaxSteps) return 1.0f
            val db = stepToDb(step, customMaxSteps)
            return dbToFloatVolume(db)
        }

        /**
         * 取得系統音量 index 在指定設備下的真實 dB 衰減值
         * API 28+ 優先向系統音訊底層查詢真實校準曲線；若不可用則使用符合人耳聽覺的擬合曲線
         */
        fun getSystemVolumeDb(
            audioManager: AudioManager?,
            index: Int,
            maxSys: Int,
            deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ): Float {
            if (index <= 0) return Float.NEGATIVE_INFINITY
            if (index >= maxSys) return MAX_DB

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && audioManager != null) {
                try {
                    val db = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType)
                    if (!db.isNaN()) {
                        return db.coerceAtMost(MAX_DB)
                    }
                } catch (e: Exception) {
                    // Fallback to mathematical curve
                }
            }

            // Fallback: Android 音訊標準指數衰減曲線 (x = index / maxSys)
            val x = (index.toFloat() / maxSys.toFloat()).coerceIn(0f, 1f)
            val db = MIN_AUDIBLE_DB * Math.pow((1.0 - x).toDouble(), 1.5).toFloat()
            return db.coerceIn(MIN_AUDIBLE_DB, MAX_DB)
        }

        /**
         * 將當前系統音量原生 index 對齊至 App 內部自訂步長 (0..customMaxSteps)
         * 依據真實分貝 (dB) 尋找最相近的自訂步長，確保進 App 瞬間體感音量完全一致無爆音
         */
        fun calculateInitialStep(
            currentSys: Int,
            maxSys: Int,
            customMaxSteps: Int,
            audioManager: AudioManager? = null,
            deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ): Int {
            if (maxSys <= 0 || currentSys <= 0) return 0
            if (currentSys >= maxSys) return customMaxSteps

            val sysDb = getSystemVolumeDb(audioManager, currentSys, maxSys, deviceType)
            if (sysDb.isInfinite() || sysDb <= -100f) return 0
            if (sysDb >= MAX_DB) return customMaxSteps

            // 依 dB 映射至 [1 .. customMaxSteps]
            val normalized = (sysDb - MIN_AUDIBLE_DB) / (MAX_DB - MIN_AUDIBLE_DB)
            val step = 1 + (normalized * (customMaxSteps - 1)).roundToInt()
            return step.coerceIn(1, customMaxSteps)
        }

        /**
         * 將 App 內部自訂步長還原為系統音量原生 index
         * 依據自訂步長之目標 dB，在系統原生 index (1..maxSys) 中尋找最接近該 dB 的級距
         */
        fun calculateRestoreSystemVolume(
            currentStep: Int,
            customMaxSteps: Int,
            maxSys: Int,
            audioManager: AudioManager? = null,
            deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ): Int {
            if (maxSys <= 0 || currentStep <= 0) return 0
            if (currentStep >= customMaxSteps) return maxSys

            val targetDb = stepToDb(currentStep, customMaxSteps)

            var bestIndex = 1
            var minDiff = Float.MAX_VALUE

            for (i in 1..maxSys) {
                val sysDb = getSystemVolumeDb(audioManager, i, maxSys, deviceType)
                if (sysDb.isInfinite()) continue
                val diff = abs(sysDb - targetDb)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIndex = i
                }
            }

            return bestIndex.coerceIn(0, maxSys)
        }

        /**
         * 在 25 段與 50 段之間切換時，精確保持 dB 體感音量不變
         */
        fun calculateScaledStep(oldStep: Int, oldMaxSteps: Int, newMaxSteps: Int): Int {
            if (oldStep <= 0) return 0
            if (oldStep >= oldMaxSteps) return newMaxSteps
            val targetDb = stepToDb(oldStep, oldMaxSteps)
            val normalized = (targetDb - MIN_AUDIBLE_DB) / (MAX_DB - MIN_AUDIBLE_DB)
            val newStep = 1 + (normalized * (newMaxSteps - 1)).roundToInt()
            return newStep.coerceIn(1, newMaxSteps)
        }
    }
}
