package com.nextcloud.musicplayer.audio

import android.content.Context
import com.nextcloud.musicplayer.playback.PlayerController
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import android.content.SharedPreferences

/**
 * Persists the requested fine step. The playback service owns system mapping and gain;
 * Activity lifecycle transitions never change either volume.
 */
class VolumeStepManager(
    private val context: Context,
    private val playerController: PlayerController
) {
    val fractions = callbackFlow {
        val prefs = context.getSharedPreferences(PlaybackVolume.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PlaybackVolume.KEY_FRACTION) trySend(PlaybackVolume.readFraction(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(PlaybackVolume.readFraction(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var currentStep: Int = 0
        private set

    var currentTotalSteps: Int = 0
        private set

    fun onEnterForeground(totalSteps: Int): Int {
        if (totalSteps <= 0) {
            currentTotalSteps = totalSteps
            currentStep = 0
            // The playback service owns gain transitions; UI restoration only restores its state.
            return currentStep
        }
        val steps = totalSteps
        val fraction = PlaybackVolume.readFraction(context)
        currentTotalSteps = steps
        currentStep = (fraction * steps).roundToInt().coerceIn(0, steps)
        return currentStep
    }

    fun onEnterBackground() = Unit

    fun setStep(step: Int, totalSteps: Int) {
        if (totalSteps <= 0) return
        val steps = totalSteps
        currentStep = step.coerceIn(0, steps)
        currentTotalSteps = steps
        val fraction = currentStep.toFloat() / steps
        PlaybackVolume.saveFraction(context, fraction)
        // SharedPreferences listener in the playback service applies the request, even in background.
    }

    fun stepUp(totalSteps: Int): Int {
        if (totalSteps <= 0) return currentStep
        onEnterForeground(totalSteps)
        val next = (currentStep + 1).coerceAtMost(totalSteps.coerceAtLeast(1))
        setStep(next, totalSteps)
        return next
    }

    fun stepDown(totalSteps: Int): Int {
        if (totalSteps <= 0) return currentStep
        onEnterForeground(totalSteps)
        val previous = (currentStep - 1).coerceAtLeast(0)
        setStep(previous, totalSteps)
        return previous
    }
}
