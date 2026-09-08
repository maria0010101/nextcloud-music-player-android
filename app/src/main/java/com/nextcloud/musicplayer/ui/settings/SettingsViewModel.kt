package com.nextcloud.musicplayer.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import com.nextcloud.musicplayer.core.settings.DataStoreManager
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlaybackCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    val repository: MusicRepository,
    private val prefsManager: SecurePreferencesManager,
    private val settingsDataStore: AppSettingsDataStore,
    private val cacheManager: PlaybackCacheManager,
    private val dataStoreManager: DataStoreManager? = null
) : ViewModel() {

    val musicFolder: StateFlow<String> = settingsDataStore.musicFolder
        .stateIn(viewModelScope, SharingStarted.Lazily, prefsManager.getSelectedMusicFolder())

    val cacheMaxSizeBytes: StateFlow<Long> = settingsDataStore.cacheMaxSizeBytes
        .stateIn(viewModelScope, SharingStarted.Lazily, cacheManager.getMaxCacheSizeBytes())

    val albumNameLevels: StateFlow<Set<Int>> = settingsDataStore.albumNameLevels
        .stateIn(viewModelScope, SharingStarted.Lazily, AppSettingsDataStore.DEFAULT_ALBUM_NAME_LEVELS)

    // 模組 2：離線下載 SAF 目錄 URI
    val downloadStorageUri: StateFlow<String?> = (dataStoreManager?.downloadStorageUri ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

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
     * 模組 1 & 2：更新離線下載 SAF 目錄並保存持久化讀寫授權
     */
    fun updateDownloadStorageUri(context: Context, uri: Uri) {
        val manager = dataStoreManager ?: DataStoreManager(context)
        manager.takePersistableUriPermission(uri)
        viewModelScope.launch {
            manager.saveDownloadStorageUri(uri.toString())
        }
    }

    /**
     * 模組 2：恢復預設離線下載目錄 (App 專屬外部空間)
     */
    fun resetDownloadStorageUri(context: Context) {
        val currentUriStr = downloadStorageUri.value
        val manager = dataStoreManager ?: DataStoreManager(context)
        if (!currentUriStr.isNullOrBlank()) {
            try {
                manager.releasePersistableUriPermission(Uri.parse(currentUriStr))
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            manager.saveDownloadStorageUri(null)
        }
    }

    fun formatDownloadStorageLocation(context: Context, uriString: String?): String {
        val manager = dataStoreManager ?: DataStoreManager(context)
        return manager.formatStorageLocation(uriString)
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

    fun quickSync() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val target = musicFolder.value
        _syncMessage.value = "開始快速增量同步..."

        viewModelScope.launch {
            val result = repository.incrementalSync(
                scopedFolder = target,
                selectedLevels = albumNameLevels.value,
                onProgress = { msg -> _syncMessage.value = msg }
            )
            _isSyncing.value = false
            if (result.isFailure) {
                _syncMessage.value = "同步失敗: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                val res = result.getOrNull()
                _syncMessage.value = "快速同步完成！新增 ${res?.addedCount ?: 0}、更新 ${res?.modifiedCount ?: 0}、刪除 ${res?.deletedCount ?: 0} 張專輯，現有 ${res?.totalTracks ?: 0} 首歌曲"
            }
        }
    }

    fun fullRescan() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val target = musicFolder.value
        _syncMessage.value = "開始強制完整重新掃描..."

        viewModelScope.launch {
            val result = repository.fullRescan(
                scopedFolder = target,
                selectedLevels = albumNameLevels.value,
                onProgress = { msg -> _syncMessage.value = msg }
            )
            _isSyncing.value = false
            if (result.isFailure) {
                _syncMessage.value = "完整重新掃描失敗: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                val res = result.getOrNull()
                _syncMessage.value = "完整重新掃描完成！共找到 ${res?.totalAlbums ?: 0} 張專輯、${res?.totalTracks ?: 0} 首歌曲"
            }
        }
    }

    fun rescanLibrary() {
        quickSync()
    }

    fun logout(onLogoutComplete: () -> Unit) {
        prefsManager.clear()
        clearCache()
        onLogoutComplete()
    }
}
