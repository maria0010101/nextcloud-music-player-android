package com.nextcloud.musicplayer.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    companion object {
        val KEY_CACHE_MAX_BYTES = longPreferencesKey("cache_max_bytes")
        val KEY_MUSIC_FOLDER = stringPreferencesKey("music_folder")
        val KEY_ALBUM_NAME_LEVELS = stringSetPreferencesKey("album_name_levels")
        val KEY_VOLUME_STEPS = intPreferencesKey("volume_steps_key")

        const val DEFAULT_CACHE_BYTES = 1024L * 1024L * 1024L // 1GB default
        val DEFAULT_ALBUM_NAME_LEVELS = setOf(2, 3) // 預設勾選階層 2、階層 3
        const val DEFAULT_VOLUME_STEPS = 15
    }

    val cacheMaxSizeBytes: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_CACHE_MAX_BYTES] ?: DEFAULT_CACHE_BYTES
    }

    val musicFolder: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_MUSIC_FOLDER] ?: ""
    }

    val albumNameLevels: Flow<Set<Int>> = context.dataStore.data.map { preferences ->
        val rawSet = preferences[KEY_ALBUM_NAME_LEVELS]
        if (rawSet == null) {
            DEFAULT_ALBUM_NAME_LEVELS
        } else {
            rawSet.mapNotNull { it.toIntOrNull() }.toSet()
        }
    }

    val volumeSteps: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOLUME_STEPS] ?: DEFAULT_VOLUME_STEPS
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

    suspend fun saveAlbumNameLevels(levels: Set<Int>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ALBUM_NAME_LEVELS] = levels.map { it.toString() }.toSet()
        }
    }

    suspend fun saveVolumeSteps(steps: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VOLUME_STEPS] = steps
        }
    }
}
