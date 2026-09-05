package com.nextcloud.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import com.nextcloud.musicplayer.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerController(
    private val context: Context,
    private val musicRepository: MusicRepository? = null
) {

    private val TAG = "PlayerController"
    private val scope = CoroutineScope(Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack: StateFlow<TrackEntity?> = _currentTrack.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var currentPlaylist = listOf<TrackEntity>()
    private var progressPollingJob: Job? = null

    fun connect() {
        if (mediaController != null || controllerFuture != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get() ?: return@addListener
                mediaController = controller
                setupPlayerListener(controller)
                updateStateFromPlayer(controller)
                Log.d(TAG, "MediaController successfully connected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressPolling()
                } else {
                    stopProgressPolling()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrack(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateStateFromPlayer(player)

                // 動態獲取音訊真實長度並寫入 Room 資料庫快取
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    val realDuration = player.duration
                    if (realDuration > 0L) {
                        _durationMs.value = realDuration
                        _currentTrack.value?.let { track ->
                            scope.launch {
                                musicRepository?.updateTrackDuration(track.id, realDuration)
                            }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer PlayerError: [${error.errorCodeName}] ${error.message}", error)
                val friendlyMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "伺服器回應錯誤 (可能是 401 認證無效或 404 找不到檔案)"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "網路連線超時，請確認伺服器狀態"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "音訊解碼失敗，不支援的格式"
                    else ->
                        "播放出錯: ${error.localizedMessage ?: error.errorCodeName}"
                }
                _playbackError.value = friendlyMessage
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleModeEnabled.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
            }
        })
    }

    private fun updateStateFromPlayer(player: Player) {
        _isPlaying.value = player.isPlaying
        _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        if (player.duration > 0L) {
            _durationMs.value = player.duration
        }
        _shuffleModeEnabled.value = player.shuffleModeEnabled
        _repeatMode.value = player.repeatMode
        updateCurrentTrack(player)

        if (player.isPlaying) {
            startProgressPolling()
        }
    }

    private fun updateCurrentTrack(player: Player) {
        val currentMediaItem = player.currentMediaItem ?: return
        val currentUri = currentMediaItem.mediaId
        val track = currentPlaylist.find { it.streamUrl == currentUri || it.id == currentUri }
        _currentTrack.value = track
        if (player.duration > 0L) {
            _durationMs.value = player.duration
        }
    }

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration
                    if (dur > 0L) {
                        _durationMs.value = dur
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0, coverUrl: String? = null) {
        val controller = mediaController ?: run {
            Log.e(TAG, "mediaController is null, cannot play")
            return
        }
        if (tracks.isEmpty()) return

        currentPlaylist = tracks
        val mediaItems = tracks.map { track ->
            val trackUri = Uri.parse(track.streamUrl)
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.format)
                .setArtworkUri(coverUrl?.let { Uri.parse(it) })
                .build()

            MediaItem.Builder()
                .setMediaId(track.streamUrl)
                .setUri(trackUri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(trackUri)
                        .build()
                )
                .setMediaMetadata(metadata)
                .build()
        }

        Log.d(TAG, "playTracks: Setting ${mediaItems.size} items, starting at index $startIndex")
        _playbackError.value = null
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun skipToNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        mediaController?.let {
            if (it.currentPosition > 3000) {
                it.seekTo(0)
            } else {
                it.seekToPreviousMediaItem()
            }
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val newMode = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = newMode
        _shuffleModeEnabled.value = newMode
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        _repeatMode.value = nextMode
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun disconnect() {
        stopProgressPolling()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }
}
