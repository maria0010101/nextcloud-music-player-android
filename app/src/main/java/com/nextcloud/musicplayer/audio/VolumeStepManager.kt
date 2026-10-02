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
        val steps = totalSteps.coerceAtLeast(1)
        val fraction = PlaybackVolume.readFraction(context)
        currentTotalSteps = steps
        currentStep = (fraction * steps).roundToInt().coerceIn(0, steps)
        playerController.setVolume(PlaybackVolume.fractionToGain(fraction))
        return currentStep
    }

    fun onEnterBackground() = Unit

    fun setStep(step: Int, totalSteps: Int) {
        val steps = totalSteps.coerceAtLeast(1)
        currentStep = step.coerceIn(0, steps)
        currentTotalSteps = steps
        val fraction = currentStep.toFloat() / steps
        PlaybackVolume.saveFraction(context, fraction)
        playerController.setVolume(PlaybackVolume.fractionToGain(fraction))
    }

    fun stepUp(totalSteps: Int): Int {
        val next = (currentStep + 1).coerceAtMost(totalSteps.coerceAtLeast(1))
        setStep(next, totalSteps)
        return next
    }

    fun stepDown(totalSteps: Int): Int {
        val previous = (currentStep - 1).coerceAtLeast(0)
        setStep(previous, totalSteps)
        return previous
    }
}
