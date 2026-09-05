package com.nextcloud.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
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

class MusicPlaybackService : MediaSessionService() {

    private val TAG = "MusicPlaybackService"
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

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

        player.addListener(object : Player.Listener {
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
            // 正確保留與注入 MediaItem 的真實 URI
            val updatedItems = mediaItems.map { item ->
                val targetUri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                    ?: android.net.Uri.parse(item.mediaId)

                item.buildUpon()
                    .setUri(targetUri)
                    .setRequestMetadata(
                        item.requestMetadata.buildUpon()
                            .setMediaUri(targetUri)
                            .build()
                    )
                    .build()
            }.toMutableList()

            Log.d(TAG, "onAddMediaItems: Enqueued ${updatedItems.size} items to player")
            return Futures.immediateFuture(updatedItems)
        }
    }
}
