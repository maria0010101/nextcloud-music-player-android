package com.nextcloud.musicplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nextcloud.musicplayer.core.network.BasicAuthInterceptor
import com.nextcloud.musicplayer.core.network.NextcloudQrLoginManager
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.auth.LoginFlowV2Client
import com.nextcloud.musicplayer.data.local.AppDatabase
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlaybackCacheManager
import com.nextcloud.musicplayer.playback.PlayerController
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class NextcloudMusicApp : Application(), ImageLoaderFactory {

    lateinit var securePreferencesManager: SecurePreferencesManager
        private set

    lateinit var authenticatedOkHttpClient: OkHttpClient
        private set

    lateinit var webDavClient: NextcloudWebDavClient
        private set

    lateinit var loginFlowClient: LoginFlowV2Client
        private set

    lateinit var qrLoginManager: NextcloudQrLoginManager
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var musicRepository: MusicRepository
        private set

    lateinit var playbackCacheManager: PlaybackCacheManager
        private set

    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()

        securePreferencesManager = SecurePreferencesManager(this)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        authenticatedOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(BasicAuthInterceptor(securePreferencesManager))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        webDavClient = NextcloudWebDavClient(authenticatedOkHttpClient, securePreferencesManager)
        loginFlowClient = LoginFlowV2Client(authenticatedOkHttpClient)
        qrLoginManager = NextcloudQrLoginManager(webDavClient, loginFlowClient, securePreferencesManager)
        database = AppDatabase.getInstance(this)
        musicRepository = MusicRepository(webDavClient, database, securePreferencesManager, this)

        playbackCacheManager = PlaybackCacheManager.getInstance(
            this,
            authenticatedOkHttpClient,
            securePreferencesManager
        )

        playerController = PlayerController(this, musicRepository)
        playerController.connect()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(authenticatedOkHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
