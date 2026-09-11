package com.nextcloud.musicplayer.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class AudioEffectManager private constructor(private val context: Context) {

    private val TAG = "AudioEffectManager"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("audio_effects_prefs", Context.MODE_PRIVATE)

    private val soundProfileManager = SoundProfileManager.getInstance(context)

    // Active audio effect instances mapped by audioSessionId
    private val dynamicsProcessors = mutableMapOf<Int, DynamicsProcessing>()
    private val fallbackEqualizers = mutableMapOf<Int, Equalizer>()

    // State flows for UI binding
    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(KEY_EQ_ENABLED, false))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqGains = MutableStateFlow(loadSavedGains())
    val eqGains: StateFlow<FloatArray> = _eqGains.asStateFlow()

    private val _activePreset = MutableStateFlow(prefs.getString(KEY_ACTIVE_PRESET, "原音 (Flat)") ?: "原音 (Flat)")
    val activePreset: StateFlow<String> = _activePreset.asStateFlow()

    private val _profileEnabled = MutableStateFlow(prefs.getBoolean(KEY_PROFILE_ENABLED, false))
    val profileEnabled: StateFlow<Boolean> = _profileEnabled.asStateFlow()

    private val _activeProfile = MutableStateFlow<HeadphoneProfile?>(null)
    val activeProfile: StateFlow<HeadphoneProfile?> = _activeProfile.asStateFlow()

    private val _channelBalance = MutableStateFlow(prefs.getFloat(KEY_CHANNEL_BALANCE, 0.0f))
    val channelBalance: StateFlow<Float> = _channelBalance.asStateFlow()

    // Fine software gain offset in dB applied by VolumeStepManager
    private var softwareGainOffsetDb: Float = 0.0f

    init {
        // Load active profile from saved name
        val savedProfileName = prefs.getString(KEY_SAVED_PROFILE_NAME, null)
        if (!savedProfileName.isNullOrBlank()) {
            _activeProfile.value = soundProfileManager.findProfile(savedProfileName)
        }
    }

    /**
     * Attach audio effects to ExoPlayer's audio session ID
     */
    fun attachSession(sessionId: Int) {
        if (sessionId <= 0) return
        if (dynamicsProcessors.containsKey(sessionId) || fallbackEqualizers.containsKey(sessionId)) {
            Log.d(TAG, "Audio session $sessionId 已附加，跳過重新初始化")
            return
        }

        var dpSuccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,     // 2 通道立體聲 (Left/Right)
                    true,  // pre-EQ (等化器 + AutoEq 校準)
                    BANDS_10.size,
                    false, // mbc
                    0,
                    false, // post-EQ
                    0,
                    true   // limiter (用於聲道平衡與防爆音)
                ).build()

                val dp = DynamicsProcessing(0, sessionId, config)
                dp.enabled = true
                dynamicsProcessors[sessionId] = dp
                dpSuccess = true
                Log.d(TAG, "已成功附加 DynamicsProcessing 至 AudioSession: $sessionId")
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing 初始化失敗，將使用 Equalizer 作為相容回退", e)
                dpSuccess = false
            }
        }

        if (!dpSuccess) {
            try {
                val eq = Equalizer(0, sessionId)
                eq.enabled = _eqEnabled.value || _profileEnabled.value
                fallbackEqualizers[sessionId] = eq
                Log.d(TAG, "已成功附加相容 Equalizer 至 AudioSession: $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "附加 Equalizer 失敗", e)
            }
        }

        applyAllEffects()
    }

    /**
     * Detach audio effects from session
     */
    fun detachSession(sessionId: Int) {
        dynamicsProcessors.remove(sessionId)?.apply {
            try {
                enabled = false
                release()
            } catch (_: Exception) {}
        }
        fallbackEqualizers.remove(sessionId)?.apply {
            try {
                enabled = false
                release()
            } catch (_: Exception) {}
        }
        Log.d(TAG, "已釋放 AudioSession: $sessionId")
    }

    // ──────────────────────────────────────────────
    // 10-Band Graphic Equalizer
    // ──────────────────────────────────────────────

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        applyAllEffects()
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until BANDS_10.size) return
        val current = _eqGains.value.copyOf()
        current[bandIndex] = gainDb.coerceIn(-12.0f, 12.0f)
        _eqGains.value = current
        _activePreset.value = "自訂 (Custom)"
        saveGains(current)
        prefs.edit().putString(KEY_ACTIVE_PRESET, "自訂 (Custom)").apply()
        applyAllEffects()
    }

    fun applyPreset(presetName: String) {
        val preset = PRESETS[presetName] ?: return
        val gains = preset.copyOf()
        _eqGains.value = gains
        _activePreset.value = presetName
        saveGains(gains)
        prefs.edit().putString(KEY_ACTIVE_PRESET, presetName).apply()
        applyAllEffects()
    }

    fun resetEq() {
        applyPreset("原音 (Flat)")
    }

    // ──────────────────────────────────────────────
    // Headphone Sound Profiles (AutoEq)
    // ──────────────────────────────────────────────

    fun setProfileEnabled(enabled: Boolean) {
        _profileEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PROFILE_ENABLED, enabled).apply()
        applyAllEffects()
    }

    fun selectHeadphoneProfile(profile: HeadphoneProfile?) {
        _activeProfile.value = profile
        prefs.edit().putString(KEY_SAVED_PROFILE_NAME, profile?.name).apply()
        if (profile != null && !_profileEnabled.value) {
            setProfileEnabled(true)
        } else {
            applyAllEffects()
        }
    }

    // ──────────────────────────────────────────────
    // Channel Balance (Left / Right)
    // ──────────────────────────────────────────────

    fun setChannelBalance(balance: Float) {
        val clamped = balance.coerceIn(-1.0f, 1.0f)
        _channelBalance.value = clamped
        prefs.edit().putFloat(KEY_CHANNEL_BALANCE, clamped).apply()
        applyChannelBalance()
    }

    // ──────────────────────────────────────────────
    // Software Volume Offset (from VolumeStepManager)
    // ──────────────────────────────────────────────

    fun setSoftwareGainOffsetDb(offsetDb: Float) {
        softwareGainOffsetDb = offsetDb.coerceIn(-30.0f, 0.0f)
        applySoftwareGain()
    }

    // ──────────────────────────────────────────────
    // Internal DSP Application
    // ──────────────────────────────────────────────

    private fun applyAllEffects() {
        applyPreEq()
        applySoftwareGain()
        applyChannelBalance()
    }

    private fun applyPreEq() {
        val eqOn = _eqEnabled.value
        val profileOn = _profileEnabled.value
        val profile = _activeProfile.value

        val profileBands = if (profileOn && profile != null) {
            BiquadMath.interpolateToGrid(profile.bands, BANDS_10)
        } else {
            FloatArray(BANDS_10.size)
        }

        val userGains = _eqGains.value
        val combinedGains = FloatArray(BANDS_10.size) { i ->
            val eg = if (eqOn) userGains[i] else 0f
            val pg = profileBands[i]
            (eg + pg).coerceIn(-24f, 24f)
        }

        val hasEqEffect = eqOn || profileOn

        // 1. DynamicsProcessing Apply
        for ((_, dp) in dynamicsProcessors) {
            try {
                val preEq = dp.getPreEqByChannelIndex(0)
                preEq.isEnabled = hasEqEffect
                dp.setPreEqAllChannelsTo(preEq)

                for (i in BANDS_10.indices) {
                    val band = dp.getPreEqBandByChannelIndex(0, i)
                    band.isEnabled = hasEqEffect
                    band.cutoffFrequency = BANDS_10[i].toFloat()
                    band.gain = combinedGains[i]
                    dp.setPreEqBandAllChannelsTo(i, band)
                }
            } catch (e: Exception) {
                Log.w(TAG, "套用 DynamicsProcessing Pre-EQ 失敗", e)
            }
        }

        // 2. Fallback Equalizer Apply
        for ((_, eq) in fallbackEqualizers) {
            try {
                eq.enabled = hasEqEffect
                val numBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                for (i in 0 until numBands) {
                    val centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000
                    val closestIdx = BANDS_10.indices.minByOrNull { abs(BANDS_10[it] - centerFreqHz) } ?: 0
                    val targetGainMb = (combinedGains[closestIdx] * 100).toInt()
                    val clamped = targetGainMb.toShort().coerceIn(range[0], range[1])
                    eq.setBandLevel(i.toShort(), clamped)
                }
            } catch (e: Exception) {
                Log.w(TAG, "套用 Fallback Equalizer 失敗", e)
            }
        }
    }

    private fun applySoftwareGain() {
        val totalGainDb = softwareGainOffsetDb
        for ((_, dp) in dynamicsProcessors) {
            try {
                dp.setInputGainAllChannelsTo(totalGainDb)
            } catch (e: Exception) {
                Log.w(TAG, "套用 DynamicsProcessing InputGain 失敗", e)
            }
        }
    }

    private fun applyChannelBalance() {
        val balance = _channelBalance.value
        val leftAttenDb = if (balance > 0f) -balance * 48f else 0f
        val rightAttenDb = if (balance < 0f) balance * 48f else 0f

        for ((_, dp) in dynamicsProcessors) {
            try {
                // Channel 0 = Left
                val leftLim = dp.getLimiterByChannelIndex(0)
                leftLim.isEnabled = true
                leftLim.postGain = leftAttenDb
                dp.setLimiterByChannelIndex(0, leftLim)

                // Channel 1 = Right
                val rightLim = dp.getLimiterByChannelIndex(1)
                rightLim.isEnabled = true
                rightLim.postGain = rightAttenDb
                dp.setLimiterByChannelIndex(1, rightLim)
            } catch (e: Exception) {
                Log.w(TAG, "套用 Channel Balance 失敗", e)
            }
        }
    }

    private fun loadSavedGains(): FloatArray {
        val raw = prefs.getString(KEY_SAVED_GAINS, null) ?: return FloatArray(BANDS_10.size)
        return try {
            val parts = raw.split(",")
            if (parts.size == BANDS_10.size) {
                FloatArray(BANDS_10.size) { parts[it].toFloat() }
            } else {
                FloatArray(BANDS_10.size)
            }
        } catch (_: Exception) {
            FloatArray(BANDS_10.size)
        }
    }

    private fun saveGains(gains: FloatArray) {
        val str = gains.joinToString(",") { it.toString() }
        prefs.edit().putString(KEY_SAVED_GAINS, str).apply()
    }

    companion object {
        val BANDS_10 = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        val PRESETS = linkedMapOf(
            "原音 (Flat)" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "重低音 (Bass Boost)" to floatArrayOf(6.0f, 5.0f, 3.5f, 1.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            "高音增強 (Treble Boost)" to floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.5f, 3.0f, 4.5f, 5.5f, 6.0f),
            "清晰人聲 (Vocal)" to floatArrayOf(-2.0f, -1.0f, 0.0f, 2.0f, 4.0f, 3.5f, 2.5f, 1.0f, 0.0f, -1.0f),
            "流行 (Pop)" to floatArrayOf(-1.0f, 1.5f, 3.0f, 4.0f, 3.0f, 1.0f, 0.0f, 1.5f, 2.5f, 2.0f),
            "搖滾 (Rock)" to floatArrayOf(4.5f, 3.0f, 1.5f, -0.5f, -1.0f, 0.5f, 2.0f, 3.5f, 4.5f, 4.0f),
            "爵士 (Jazz)" to floatArrayOf(3.0f, 2.0f, 0.5f, 1.0f, 1.5f, 1.5f, 0.5f, 1.5f, 2.5f, 3.5f),
            "古典 (Classical)" to floatArrayOf(4.0f, 3.0f, 2.0f, 1.0f, -0.5f, 0.0f, 1.5f, 2.5f, 3.0f, 3.5f),
            "電子舞曲 (Electronic)" to floatArrayOf(5.5f, 4.0f, 2.0f, 0.0f, -1.5f, 1.0f, 2.0f, 3.5f, 4.5f, 4.0f),
            "原聲木吉他 (Acoustic)" to floatArrayOf(3.0f, 2.0f, 1.0f, 1.5f, 1.5f, 2.0f, 2.5f, 2.5f, 2.0f, 1.0f)
        )

        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_SAVED_GAINS = "saved_gains"
        private const val KEY_ACTIVE_PRESET = "active_preset"
        private const val KEY_PROFILE_ENABLED = "profile_enabled"
        private const val KEY_SAVED_PROFILE_NAME = "saved_profile_name"
        private const val KEY_CHANNEL_BALANCE = "channel_balance"

        @Volatile
        private var instance: AudioEffectManager? = null

        fun getInstance(context: Context): AudioEffectManager {
            return instance ?: synchronized(this) {
                instance ?: AudioEffectManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
