package com.nextcloud.musicplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.nextcloud.musicplayer.core.settings.AudioPlaybackSettings
import kotlin.math.ceil
import kotlin.math.pow

/** Mapping independent of Android, using the actual route's dB curve rather than index ratios. */
object DynamicVolumeMapping {
    data class Result(val systemStep: Int, val gain: Float)

    fun map(fraction: Float, max: Int, dbAt: (Int) -> Float): Result {
        if (max <= 0) return Result(0, 0f)
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        if (f == 0f) return Result(0, 0f)
        val position = f * max
        val low = position.toInt().coerceIn(0, max)
        // Nearest usable integer: rounding down would need amplification, which Media3
        // cannot provide and could clip full-scale PCM. Use the adjacent upper step.
        val high = ceil(position.toDouble()).toInt().coerceIn(1, max)
        fun finiteDb(index: Int): Float = dbAt(index).takeIf { it.isFinite() }
            ?: if (index == 0) (dbAt(1).takeIf { it.isFinite() } ?: -60f) - 6f
            else (-60f * (1f - index.toFloat() / max))
        val targetDb = finiteDb(low) + (finiteDb(high) - finiteDb(low)) * (position - low)
        return Result(high, 10.0.pow(((targetDb - finiteDb(high)) / 20f).toDouble()).toFloat().coerceIn(0f, 1f))
    }
}

/** Service-owned: UI and background use exactly the same persisted target and gain. */
class DynamicVolume(private val context: Context, private val reapply: () -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences(PlaybackVolume.PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var policy = AudioPlaybackSettings()
    private var expectedStep = -1
    private var started = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PlaybackVolume.KEY_FRACTION) handler.post { if (started && policy.usesAppVolume) reapply() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!policy.usesAppVolume) return
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (actual == expectedStep) return // our own write, including coalesced broadcasts
            expectedStep = actual
            // System panel/background keys own this new level. Drop the old residual so
            // returning to the Activity cannot restore a stale, attenuated output.
            PlaybackVolume.saveFraction(context ?: this@DynamicVolume.context,
                actual.toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
            reapply()
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { if (started && policy.usesAppVolume) adoptSystemVolume() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { if (started && policy.usesAppVolume) adoptSystemVolume() }
    }

    private fun adoptSystemVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (actual != expectedStep) {
            expectedStep = actual
            PlaybackVolume.saveFraction(context, actual.toFloat() / max)
        }
        reapply()
    }

    fun gainFor(settings: AudioPlaybackSettings): Float {
        val enteringFine = settings.usesAppVolume && !policy.usesAppVolume
        policy = settings
        val wasDynamic = prefs.getBoolean("dynamic_active", false)
        prefs.edit().putBoolean("dynamic_active", settings.usesAppVolume).apply()
        if (!settings.usesAppVolume) return 1f // off/strict: never write AudioManager
        // First migration/activation starts at the current hardware level, not a stale
        // app-local 100% setting. Lifecycle and engine changes do not enter this branch.
        if (enteringFine && (!wasDynamic || prefs.getInt("dynamic_system_step", -1) != audio.getStreamVolume(AudioManager.STREAM_MUSIC))) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            PlaybackVolume.saveFraction(context, audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val route = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.isSink && it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) }
            ?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val mapping = DynamicVolumeMapping.map(PlaybackVolume.readFraction(context), max) { index ->
            try { if (android.os.Build.VERSION.SDK_INT < 28) Float.NaN else audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, route) }
            catch (_: IllegalArgumentException) { Float.NaN }
        }
        expectedStep = mapping.systemStep
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, mapping.systemStep, 0) }
        catch (_: SecurityException) { /* fixed-volume / DND restrictions: use actual index below */ }
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (actual != mapping.systemStep) {
            // Safe-volume limits must not be defeated by software amplification.
            expectedStep = actual
            PlaybackVolume.saveFraction(context, actual.toFloat() / max.coerceAtLeast(1))
            return if (actual == 0) 0f else 1f
        }
        prefs.edit().putInt("dynamic_system_step", actual).apply()
        return mapping.gain
    }

    fun start() {
        started = true
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
    }
    fun close() {
        started = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        context.unregisterReceiver(receiver)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        handler.removeCallbacksAndMessages(null)
    }
}
