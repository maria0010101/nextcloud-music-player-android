package com.nextcloud.musicplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlaybackCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    val repository: MusicRepository,
    private val prefsManager: SecurePreferencesManager,
    private val settingsDataStore: AppSettingsDataStore,
    private val cacheManager: PlaybackCacheManager
) : ViewModel() {

    val musicFolder: StateFlow<String> = settingsDataStore.musicFolder
        .stateIn(viewModelScope, SharingStarted.Lazily, prefsManager.getSelectedMusicFolder())

    val cacheMaxSizeBytes: StateFlow<Long> = settingsDataStore.cacheMaxSizeBytes
        .stateIn(viewModelScope, SharingStarted.Lazily, cacheManager.getMaxCacheSizeBytes())

    val albumNameLevels: StateFlow<Set<Int>> = settingsDataStore.albumNameLevels
        .stateIn(viewModelScope, SharingStarted.Lazily, AppSettingsDataStore.DEFAULT_ALBUM_NAME_LEVELS)

    private val _usedCacheBytes = MutableStateFlow(cacheManager.getCacheSizeBytes())
    val usedCacheBytes: StateFlow<Long> = _usedCacheBytes.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    val serverUrl: String = prefsManager.getServerUrl() ?: "未連線"
    val loginName: String = prefsManager.getLoginName() ?: "未知"

    init {
        refreshUsedCache()
    }

    fun refreshUsedCache() {
        _usedCacheBytes.value = cacheManager.getCacheSizeBytes()
    }

    fun updateMusicFolder(newFolder: String) {
        val clean = newFolder.trim().trim('/')
        viewModelScope.launch {
            settingsDataStore.saveMusicFolder(clean)
            prefsManager.saveSelectedMusicFolder(clean)
            rescanLibrary()
        }
    }

    /**
     * 模組 5：動態快取上限套用與持久化儲存
     */
    fun updateCacheMaxSize(newSizeBytes: Long) {
        viewModelScope.launch {
            settingsDataStore.saveCacheMaxBytes(newSizeBytes)
            cacheManager.updateMaxCacheSizeBytes(newSizeBytes)
            refreshUsedCache()
        }
    }

    fun updateAlbumNameLevels(newLevels: Set<Int>) {
        viewModelScope.launch {
            settingsDataStore.saveAlbumNameLevels(newLevels)
            repository.reapplyAlbumNameLevels(newLevels, musicFolder.value)
        }
    }

    fun previewAlbumName(selectedLevels: Set<Int>): String {
        val sampleLevels = listOf("Music", "Rock", "Classic", "Pink Floyd", "The Wall")
        return MusicRepository.formatAlbumName(sampleLevels, selectedLevels, "The Wall")
    }

    fun clearCache() {
        cacheManager.clearCache()
        refreshUsedCache()
    }

    fun rescanLibrary() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val target = musicFolder.value
        _syncMessage.value = "開始連線掃描 /$target..."

        viewModelScope.launch {
            val result = repository.scanMusicLibrary(
                scopedFolder = target,
                selectedLevels = albumNameLevels.value,
                onProgress = { msg -> _syncMessage.value = msg }
            )
            _isSyncing.value = false
            if (result.isFailure) {
                _syncMessage.value = "掃描失敗: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                val count = result.getOrDefault(0)
                _syncMessage.value = "掃描同步完成！共找到 $count 首歌曲"
            }
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        prefsManager.clear()
        clearCache()
        onLogoutComplete()
    }
}
