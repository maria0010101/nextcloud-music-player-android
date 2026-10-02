package com.nextcloud.musicplayer.audio

import android.content.Context
import kotlin.math.pow

/** Persist the app's own volume independently of the Android media stream. */
object PlaybackVolume {
    private const val PREFS_NAME = "playback_volume"
    private const val KEY_FRACTION = "fraction"

    fun readFraction(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FRACTION, 1f).coerceIn(0f, 1f)

    fun saveFraction(context: Context, fraction: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FRACTION, fraction.coerceIn(0f, 1f)).apply()
    }

    fun readGain(context: Context): Float = fractionToGain(readFraction(context))

    /** 40 dB perceptual range; zero is an actual mute, not a nearly silent output. */
    fun fractionToGain(fraction: Float): Float {
        val value = fraction.coerceIn(0f, 1f)
        if (value == 0f) return 0f
        return 10.0.pow((-40.0 * (1.0 - value)) / 20.0).toFloat()
    }
}
