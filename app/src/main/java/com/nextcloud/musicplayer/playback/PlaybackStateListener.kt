package com.nextcloud.musicplayer.playback

sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Buffering(val message: String = "檔案緩衝暫存中，請稍候...") : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Ended : PlaybackState()
    data class Error(val reason: String, val rawMessage: String? = null) : PlaybackState()
}
