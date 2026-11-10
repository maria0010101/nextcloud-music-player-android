package com.nextcloud.musicplayer.audio

import android.content.Context
import com.nextcloud.musicplayer.playback.PlayerController
import kotlin.math.roundToInt

/**
 * App-local volume steps. Android's output volume remains the user's device setting;
 * neither lifecycle changes nor fine steps write to the global music stream.
 */
class VolumeStepManager(
    private val context: Context,
    private val playerController: PlayerController
) {
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
        playerController.setVolume(PlaybackVolume.fractionToGain(fraction))
    }

    fun stepUp(totalSteps: Int): Int {
        if (totalSteps <= 0) return currentStep
        val next = (currentStep + 1).coerceAtMost(totalSteps.coerceAtLeast(1))
        setStep(next, totalSteps)
        return next
    }

    fun stepDown(totalSteps: Int): Int {
        if (totalSteps <= 0) return currentStep
        val previous = (currentStep - 1).coerceAtLeast(0)
        setStep(previous, totalSteps)
        return previous
    }
}
