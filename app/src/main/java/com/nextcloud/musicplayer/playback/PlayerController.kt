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

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

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
                Log.d(TAG, "MediaController 已成功連線")
            } catch (e: Exception) {
                Log.e(TAG, "MediaController 連線失敗", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    _playbackState.value = PlaybackState.Playing
                    startProgressPolling()
                } else {
                    if (player.playbackState == Player.STATE_READY) {
                        _playbackState.value = PlaybackState.Paused
                    }
                    stopProgressPolling()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrack(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> {
                        _playbackState.value = PlaybackState.Idle
                    }
                    Player.STATE_BUFFERING -> {
                        _playbackState.value = PlaybackState.Buffering("檔案緩衝暫存中，請稍候...")
                        Log.d(TAG, "ExoPlayer 狀態: 檔案緩衝暫存中...")
                    }
                    Player.STATE_READY -> {
                        _playbackState.value = if (player.isPlaying) PlaybackState.Playing else PlaybackState.Paused
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
                    Player.STATE_ENDED -> {
                        _playbackState.value = PlaybackState.Ended
                    }
                }
                updateStateFromPlayer(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer 播放錯誤: [${error.errorCodeName} / ${error.errorCode}]: ${error.message}", error)
                val friendlyMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "無法載入：伺服器驗證過期或無權限 (HTTP 401)"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "無法載入：雲端檔案不存在 (HTTP 404)"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "網路連線超時：請確認 Nextcloud 伺服器狀態"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "音訊解碼失敗：不支援的音訊編碼格式"
                    else ->
                        "播放失敗: ${error.localizedMessage ?: error.errorCodeName}"
                }
                _playbackState.value = PlaybackState.Error(friendlyMessage, error.message)
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
        val track = currentPlaylist.find {
            it.streamUrl == currentUri || it.id == currentUri || it.playableUri == currentUri
        }
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
            Log.e(TAG, "mediaController is null, connect first")
            return
        }
        if (tracks.isEmpty()) return

        currentPlaylist = tracks
        val mediaItems = tracks.map { track ->
            // 優先使用可離線播放的本地 file:// URI，若未下載則使用 Nextcloud 串流 URL
            val playbackUrl = track.playableUri
            val playUri = Uri.parse(playbackUrl)
            val effectiveCover = track.coverUrl ?: coverUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.format)
                .setArtworkUri(effectiveCover?.let { Uri.parse(it) })
                .build()

            MediaItem.Builder()
                .setMediaId(playbackUrl)
                .setUri(playUri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(playUri)
                        .build()
                )
                .setMediaMetadata(metadata)
                .build()
        }

        Log.d(TAG, "playTracks: 設定佇列共 ${mediaItems.size} 首，由第 $startIndex 首開始播放")
        _playbackError.value = null
        _playbackState.value = PlaybackState.Buffering("檔案緩衝暫存中，請稍候...")

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
