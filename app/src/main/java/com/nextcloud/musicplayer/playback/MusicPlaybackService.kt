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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nextcloud.musicplayer.MainActivity
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.audio.AudioEffectManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : MediaSessionService() {

    private val TAG = "MusicPlaybackService"
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(cacheManager.createMediaSourceFactory())
            .setAudioAttributes(audioAttributes, true) // 自動管理 Audio Focus (暫停/淡出)
            .setHandleAudioBecomingNoisy(true)        // 拔耳機自動暫停
            .setWakeMode(C.WAKE_MODE_NETWORK)         // 串流播放時維持 CPU 喚醒
            .build()

        if (player.audioSessionId > 0) {
            AudioEffectManager.getInstance(applicationContext).attachSession(player.audioSessionId)
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "ExoPlayer onAudioSessionIdChanged: audioSessionId=$audioSessionId")
                if (audioSessionId > 0) {
                    AudioEffectManager.getInstance(applicationContext).attachSession(audioSessionId)
                }
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
        try {
            if (::player.isInitialized && player.audioSessionId > 0) {
                AudioEffectManager.getInstance(applicationContext).detachSession(player.audioSessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioEffectManager detachSession 失敗", e)
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
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
