package com.nextcloud.musicplayer.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    companion object {
        val KEY_CACHE_MAX_BYTES = longPreferencesKey("cache_max_bytes")
        val KEY_MUSIC_FOLDER = stringPreferencesKey("music_folder")

        const val DEFAULT_CACHE_BYTES = 1024L * 1024L * 1024L // 1GB default
    }

    val cacheMaxSizeBytes: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_CACHE_MAX_BYTES] ?: DEFAULT_CACHE_BYTES
    }

    val musicFolder: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_MUSIC_FOLDER] ?: ""
    }

    suspend fun saveCacheMaxBytes(bytes: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CACHE_MAX_BYTES] = bytes
        }
    }

    suspend fun saveMusicFolder(folder: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MUSIC_FOLDER] = folder.trim().trim('/')
        }
    }
}
