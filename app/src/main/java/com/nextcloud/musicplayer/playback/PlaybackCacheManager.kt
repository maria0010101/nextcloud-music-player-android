package com.nextcloud.musicplayer.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackCacheManager private constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val prefsManager: SecurePreferencesManager,
    private val settingsDataStore: AppSettingsDataStore? = null
) {

    private val TAG = "PlaybackCacheManager"
    private val cacheDir = File(context.cacheDir, "media3_audio_cache")

    @Volatile
    private var currentMaxCacheSizeBytes: Long = runBlocking {
        settingsDataStore?.cacheMaxSizeBytes?.first() ?: AppSettingsDataStore.DEFAULT_CACHE_BYTES
    }

    private val databaseProvider = StandaloneDatabaseProvider(context)
    private var evictor = LeastRecentlyUsedCacheEvictor(currentMaxCacheSizeBytes)

    val simpleCache: SimpleCache by lazy {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        SimpleCache(cacheDir, evictor, databaseProvider)
    }

    private val upstreamDataSourceFactory by lazy {
        val defaultProperties = mutableMapOf(
            "User-Agent" to "NextcloudMusicPlayer/1.0 (Android)",
            "OCS-APIREQUEST" to "true"
        )
        prefsManager.getBasicAuthHeader()?.let { auth ->
            defaultProperties["Authorization"] = auth
            Log.d(TAG, "已配置帶有 Basic Auth 的 OkHttpDataSource 串流工廠")
        }

        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("NextcloudMusicPlayer/1.0 (Android)")
            .setDefaultRequestProperties(defaultProperties)

        DefaultDataSource.Factory(context, okHttpFactory)
    }

    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(simpleCache)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun createMediaSourceFactory(): MediaSource.Factory {
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheDataSourceFactory)
    }

    /**
     * 模組 5：動態更新快取上限 (即時套用)
     */
    fun updateMaxCacheSizeBytes(newSizeBytes: Long) {
        currentMaxCacheSizeBytes = newSizeBytes
        Log.i(TAG, "動態更新快取上限至: ${newSizeBytes / (1024 * 1024)} MB")

        // 若當前已用快取空間超過新設定上限，主動移除最舊資源
        try {
            while (simpleCache.cacheSpace > newSizeBytes && simpleCache.keys.isNotEmpty()) {
                val oldestKey = simpleCache.keys.firstOrNull() ?: break
                simpleCache.removeResource(oldestKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "動態調整快取清理異常", e)
        }
    }

    fun getCacheSizeBytes(): Long {
        return try {
            simpleCache.cacheSpace
        } catch (e: Exception) {
            0L
        }
    }

    fun getMaxCacheSizeBytes(): Long {
        return currentMaxCacheSizeBytes
    }

    fun clearCache() {
        try {
            simpleCache.keys.forEach { key ->
                simpleCache.removeResource(key)
            }
            Log.i(TAG, "已清除全部音訊快取")
        } catch (e: Exception) {
            Log.e(TAG, "清理快取失敗", e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PlaybackCacheManager? = null

        fun getInstance(
            context: Context,
            okHttpClient: OkHttpClient,
            prefsManager: SecurePreferencesManager,
            settingsDataStore: AppSettingsDataStore? = null
        ): PlaybackCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackCacheManager(
                    context.applicationContext,
                    okHttpClient,
                    prefsManager,
                    settingsDataStore
                ).also { INSTANCE = it }
            }
        }
    }
}
