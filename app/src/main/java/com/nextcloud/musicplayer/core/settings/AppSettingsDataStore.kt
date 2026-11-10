package com.nextcloud.musicplayer.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    companion object {
        val KEY_CACHE_MAX_BYTES = longPreferencesKey("cache_max_bytes")
        val KEY_MUSIC_FOLDER = stringPreferencesKey("music_folder")
        val KEY_ALBUM_NAME_LEVELS = stringSetPreferencesKey("album_name_levels")
        val KEY_VOLUME_STEPS = intPreferencesKey("volume_steps_key")
        val KEY_AUDIO_ENGINE = stringPreferencesKey("audio_engine")
        val PREF_ENABLE_ALBUM_TITLE_MARQUEE = booleanPreferencesKey("pref_enable_album_title_marquee")
        val PREF_ENABLE_TRACK_TITLE_MARQUEE = booleanPreferencesKey("pref_enable_track_title_marquee")
        val PREF_ALBUM_VIEW_MODE = intPreferencesKey("pref_album_view_mode")

        const val DEFAULT_CACHE_BYTES = 1024L * 1024L * 1024L // 1GB default
        val DEFAULT_ALBUM_NAME_LEVELS = setOf(2, 3) // 預設勾選階層 2、階層 3
        const val AUDIO_ENGINE_SYSTEM = AudioPlaybackSettings.ENGINE_SYSTEM
        const val AUDIO_ENGINE_FLICK = AudioPlaybackSettings.ENGINE_FLICK
        const val VOLUME_SYSTEM = AudioPlaybackSettings.VOLUME_SYSTEM
        const val VOLUME_BIT_PERFECT = AudioPlaybackSettings.VOLUME_BIT_PERFECT
        const val DEFAULT_VOLUME_STEPS = VOLUME_SYSTEM
        const val DEFAULT_ENABLE_ALBUM_TITLE_MARQUEE = false
        const val DEFAULT_ENABLE_TRACK_TITLE_MARQUEE = true
        const val DEFAULT_ALBUM_VIEW_MODE = 2 // 預設 2 欄網格
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

    val playbackSettings: Flow<AudioPlaybackSettings> = context.dataStore.data.map { preferences ->
        AudioPlaybackSettings.normalize(preferences[KEY_AUDIO_ENGINE], preferences[KEY_VOLUME_STEPS])
    }.distinctUntilChanged()

    val audioEngine: Flow<String> = playbackSettings.map { it.audioEngine }
    val volumeSteps: Flow<Int> = playbackSettings.map { it.volumeSteps }

    val enableAlbumTitleMarquee: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PREF_ENABLE_ALBUM_TITLE_MARQUEE] ?: DEFAULT_ENABLE_ALBUM_TITLE_MARQUEE
    }

    val enableTrackTitleMarquee: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PREF_ENABLE_TRACK_TITLE_MARQUEE] ?: DEFAULT_ENABLE_TRACK_TITLE_MARQUEE
    }

    val albumViewModeId: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PREF_ALBUM_VIEW_MODE] ?: DEFAULT_ALBUM_VIEW_MODE
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
            val policy = AudioPlaybackSettings.normalize(preferences[KEY_AUDIO_ENGINE], steps)
            preferences[KEY_VOLUME_STEPS] = policy.volumeSteps
        }
    }

    suspend fun saveAudioEngine(engine: String) {
        context.dataStore.edit { preferences ->
            val policy = AudioPlaybackSettings.normalize(engine, preferences[KEY_VOLUME_STEPS])
            preferences[KEY_AUDIO_ENGINE] = policy.audioEngine
            preferences[KEY_VOLUME_STEPS] = policy.volumeSteps
        }
    }

    suspend fun saveEnableAlbumTitleMarquee(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PREF_ENABLE_ALBUM_TITLE_MARQUEE] = enabled
        }
    }

    suspend fun saveEnableTrackTitleMarquee(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PREF_ENABLE_TRACK_TITLE_MARQUEE] = enabled
        }
    }

    suspend fun saveAlbumViewModeId(modeId: Int) {
        context.dataStore.edit { preferences ->
            preferences[PREF_ALBUM_VIEW_MODE] = modeId
        }
    }
}
