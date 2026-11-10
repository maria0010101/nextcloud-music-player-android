package com.nextcloud.musicplayer.core.settings

/** Atomic playback policy, shared by the service and UI. Fine volume is mapped by the playback service. */
data class AudioPlaybackSettings(
    val audioEngine: String = ENGINE_SYSTEM,
    val volumeSteps: Int = VOLUME_SYSTEM
) {
    val isFlick: Boolean get() = audioEngine == ENGINE_FLICK
    val isBitPerfect: Boolean get() = isFlick && volumeSteps == VOLUME_BIT_PERFECT
    val usesAppVolume: Boolean get() = volumeSteps in FINE_STEPS

    fun effectiveGain(savedGain: Float): Float =
        if (usesAppVolume) savedGain.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f else 1f

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_FLICK = "flick"
        const val VOLUME_SYSTEM = 0
        const val VOLUME_BIT_PERFECT = -1
        val FINE_STEPS = setOf(25, 50, 100)

        fun normalize(engine: String?, steps: Int?): AudioPlaybackSettings {
            val selectedEngine = if (engine == ENGINE_FLICK) ENGINE_FLICK else ENGINE_SYSTEM
            val selectedSteps = when {
                steps in FINE_STEPS -> steps!!
                steps == VOLUME_BIT_PERFECT && selectedEngine == ENGINE_FLICK -> VOLUME_BIT_PERFECT
                // Legacy 15/30 and unknown values mean system volume, not a hidden precision mode.
                else -> VOLUME_SYSTEM
            }
            return AudioPlaybackSettings(selectedEngine, selectedSteps)
        }
    }
}
