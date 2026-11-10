package com.nextcloud.musicplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import com.nextcloud.musicplayer.core.settings.AudioPlaybackSettings
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nextcloud.musicplayer.MainActivity
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.audio.AudioEffectManager
import com.nextcloud.musicplayer.audio.PlaybackVolume
import com.nextcloud.musicplayer.audio.DynamicVolume

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : MediaSessionService() {

    private val TAG = "MusicPlaybackService"
    private var mediaSession: MediaSession? = null
    private lateinit var player: Player
    private lateinit var systemPlayer: ExoPlayer
    private lateinit var guardedSystemPlayer: Player
    private var flickPlayer: FlickPlayer? = null
    private var guardedFlickPlayer: Player? = null
    private var sourceServer: FlickDataSourceServer? = null
    private var policy = AudioPlaybackSettings(AppSettingsDataStore.AUDIO_ENGINE_SYSTEM, 0)
    private var effectSession = 0
    private lateinit var dynamicVolume: DynamicVolume
    private var mappedGain = 1f

    private fun applyDynamicVolume() {
        mappedGain = dynamicVolume.gainFor(policy)
        player.volume = mappedGain
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val app = application as NextcloudMusicApp
        val cacheManager = app.playbackCacheManager

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        policy = runBlocking(Dispatchers.IO) { app.appSettingsDataStore.playbackSettings.first() }
        dynamicVolume = DynamicVolume(this, ::applyDynamicVolume)
        mappedGain = dynamicVolume.gainFor(policy)
        systemPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(cacheManager.createMediaSourceFactory())
            .setAudioAttributes(audioAttributes, true) // 自動管理 Audio Focus (暫停/淡出)
            .setHandleAudioBecomingNoisy(true)        // 拔耳機自動暫停
            .setWakeMode(C.WAKE_MODE_NETWORK)         // 串流播放時維持 CPU 喚醒
            .build()

        guardedSystemPlayer = object : ForwardingPlayer(systemPlayer) {
            override fun setVolume(volume: Float) {
                super.setVolume(if (policy.usesAppVolume) volume.coerceIn(0f, 1f) else 1f)
            }
        }
        player = if (policy.isFlick) getFlickPlayer() else guardedSystemPlayer
        player.volume = mappedGain
        updateSystemEffects()
        dynamicVolume.start()

        systemPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "ExoPlayer onAudioSessionIdChanged: audioSessionId=$audioSessionId")
                if (player === guardedSystemPlayer) updateSystemEffects()
            }

            override fun onVolumeChanged(volume: Float) {
                Log.d(TAG, "ExoPlayer onVolumeChanged: volume=$volume")
            }

            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                Log.d(TAG, "ExoPlayer onDeviceVolumeChanged: volume=$volume, muted=$muted")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error [${error.errorCodeName} / ${error.errorCode}]: ${error.message}", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> Log.d(TAG, "Player ready. Duration: ${player.duration}ms")
                    Player.STATE_BUFFERING -> Log.d(TAG, "Player buffering...")
                    Player.STATE_ENDED -> Log.d(TAG, "Playback ended")
                    Player.STATE_IDLE -> Log.d(TAG, "Player idle")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                // 當切換曲目且未帶有 artworkData 時，非同步獲取並注入至 PlaylistMetadata
                val metadata = mediaItem.mediaMetadata
                if (metadata.artworkData == null && metadata.artworkUri != null) {
                    val uriStr = metadata.artworkUri.toString()
                    serviceScope.launch(Dispatchers.IO) {
                        val bytes = ArtworkHelper.getOrLoadArtworkBytes(applicationContext, uriStr)
                        if (bytes != null) {
                            withContext(Dispatchers.Main) {
                                val current = player.currentMediaItem
                                if (current != null && current.mediaId == mediaItem.mediaId) {
                                    val updatedMetadata = current.mediaMetadata.buildUpon()
                                        .setArtworkData(bytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                        .build()
                                    player.setPlaylistMetadata(updatedMetadata)
                                    Log.d(TAG, "onMediaItemTransition: 已注入封面 Byte 陣列 (${bytes.size} bytes)")
                                }
                            }
                        }
                    }
                }
            }
        })

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(MediaSessionCallback())
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        serviceScope.launch {
            app.appSettingsDataStore.playbackSettings.collect { settings -> applySettings(settings) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun getFlickPlayer(): Player {
        return guardedFlickPlayer ?: run {
            val app = application as NextcloudMusicApp
            val server = FlickDataSourceServer(app.playbackCacheManager.cacheDataSourceFactory)
            sourceServer = server
            val backend = FlickPlayer(
                context = this,
                strict = policy.isBitPerfect,
                initialGain = mappedGain,
                headers = { emptyMap() },
                resolveUri = { server.resolve(it) },
                onNativeReleased = { server.close() }
            ).also { flickPlayer = it }
            object : ForwardingPlayer(backend) {
                override fun setVolume(volume: Float) {
                    if (!policy.isBitPerfect) super.setVolume(if (policy.usesAppVolume) volume.coerceIn(0f, 1f) else 1f)
                }
            }.also { guardedFlickPlayer = it }
        }
    }

    @OptIn(UnstableApi::class)
    private fun applySettings(settings: AudioPlaybackSettings) {
        val previous = policy
        if (previous.volumeSteps != settings.volumeSteps &&
            (previous.isBitPerfect || settings.isBitPerfect || (previous.usesAppVolume && !settings.usesAppVolume))) {
            player.pause()
        }
        policy = settings
        val gain = dynamicVolume.gainFor(settings)
        mappedGain = gain
        if (previous.audioEngine != settings.audioEngine) {
            val old = player
            val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
            val index = old.currentMediaItemIndex.coerceAtLeast(0)
            val position = old.currentPosition.coerceAtLeast(0)
            val repeat = old.repeatMode
            val shuffle = old.shuffleModeEnabled
            old.pause()
            old.stop()
            player = if (settings.isFlick) getFlickPlayer() else guardedSystemPlayer
            flickPlayer?.updateVolumeMode(settings.isBitPerfect, gain)
            player.volume = gain
            player.repeatMode = repeat
            player.shuffleModeEnabled = shuffle
            if (items.isNotEmpty()) {
                player.setMediaItems(items, index.coerceAtMost(items.lastIndex), position)
                // Changing engines remains paused: do not start a new output at unity gain.
                player.playWhenReady = false
                player.prepare()
            }
            mediaSession?.setPlayer(player)
        } else {
            flickPlayer?.updateVolumeMode(settings.isBitPerfect, gain)
            player.volume = gain
        }
        updateSystemEffects()
    }

    private fun updateSystemEffects() {
        val effects = AudioEffectManager.getInstance(applicationContext)
        if (effectSession > 0 && (player !== guardedSystemPlayer || effectSession != systemPlayer.audioSessionId)) {
            effects.detachSession(effectSession)
            effectSession = 0
        }
        if (player === guardedSystemPlayer && systemPlayer.audioSessionId > 0 && effectSession != systemPlayer.audioSessionId) {
            effectSession = systemPlayer.audioSessionId
            effects.attachSession(effectSession)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::dynamicVolume.isInitialized) dynamicVolume.close()
        if (effectSession > 0) {
            AudioEffectManager.getInstance(applicationContext).detachSession(effectSession)
            effectSession = 0
        }
        mediaSession?.release()
        mediaSession = null
        if (::systemPlayer.isInitialized) systemPlayer.release()
        flickPlayer?.release() // The loopback server closes after serialized native teardown.
        if (flickPlayer == null) sourceServer?.close()
        super.onDestroy()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // 正確保留與注入 MediaItem 的真實 URI，並檢查快取的封面 Byte 陣列
            val updatedItems = mediaItems.map { item ->
                val targetUri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                    ?: android.net.Uri.parse(item.mediaId)

                val artUri = item.mediaMetadata.artworkUri
                val cachedBytes = if (artUri != null) ArtworkHelper.getCachedArtworkBytes(artUri.toString()) else null

                val metaBuilder = item.mediaMetadata.buildUpon()
                if (item.mediaMetadata.artworkData == null && cachedBytes != null) {
                    metaBuilder.setArtworkData(cachedBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }

                item.buildUpon()
                    .setUri(targetUri)
                    .setRequestMetadata(
                        item.requestMetadata.buildUpon()
                            .setMediaUri(targetUri)
                            .build()
                    )
                    .setMediaMetadata(metaBuilder.build())
                    .build()
            }.toMutableList()

            Log.d(TAG, "onAddMediaItems: Enqueued ${updatedItems.size} items to player")
            return Futures.immediateFuture(updatedItems)
        }
    }
}
