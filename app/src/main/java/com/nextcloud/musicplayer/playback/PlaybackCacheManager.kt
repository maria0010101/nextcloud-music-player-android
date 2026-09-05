package com.nextcloud.musicplayer.playback

import android.content.Context
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
import okhttp3.OkHttpClient
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackCacheManager private constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val cacheDir = File(context.cacheDir, "media3_audio_cache")
    private val maxCacheSizeBytes = 1024L * 1024L * 1024L // 1GB LRU Cache Limit
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSizeBytes)

    val simpleCache: SimpleCache by lazy {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        SimpleCache(cacheDir, evictor, databaseProvider)
    }

    private val upstreamDataSourceFactory by lazy {
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
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

    fun getCacheSizeBytes(): Long {
        return try {
            simpleCache.cacheSpace
        } catch (e: Exception) {
            0L
        }
    }

    fun clearCache() {
        try {
            simpleCache.keys.forEach { key ->
                simpleCache.removeResource(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PlaybackCacheManager? = null

        fun getInstance(context: Context, okHttpClient: OkHttpClient): PlaybackCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackCacheManager(
                    context.applicationContext,
                    okHttpClient
                ).also { INSTANCE = it }
            }
        }
    }
}
