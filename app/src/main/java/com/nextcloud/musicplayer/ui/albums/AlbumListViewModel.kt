package com.nextcloud.musicplayer.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumListViewModel(
    val repository: MusicRepository,
    private val prefsManager: SecurePreferencesManager
) : ViewModel() {

    val rawAlbums: StateFlow<List<AlbumEntity>> = repository.getAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridMode = MutableStateFlow(true)
    val isGridMode: StateFlow<Boolean> = _isGridMode.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    private val _selectedFolder = MutableStateFlow(prefsManager.getSelectedMusicFolder())
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    val filteredAlbums: StateFlow<List<AlbumEntity>> = combine(rawAlbums, _searchQuery) { albums, query ->
        if (query.isBlank()) {
            albums
        } else {
            albums.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleGridMode() {
        _isGridMode.value = !_isGridMode.value
    }

    fun updateSelectedFolder(newFolder: String) {
        val clean = newFolder.trim().trim('/')
        _selectedFolder.value = clean
        prefsManager.saveSelectedMusicFolder(clean)
        syncLibrary()
    }

    fun syncLibrary() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val targetFolder = _selectedFolder.value
        val display = if (targetFolder.isEmpty()) "根目錄" else "/$targetFolder"
        _syncMessage.value = "開始連線掃描 $display..."

        viewModelScope.launch {
            val result = repository.scanMusicLibrary(
                scopedFolder = targetFolder,
                onProgress = { msg -> _syncMessage.value = msg }
            )
            _isSyncing.value = false
            if (result.isFailure) {
                _syncMessage.value = "掃描失敗: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                val count = result.getOrDefault(0)
                _syncMessage.value = "掃描完成！共同步 $count 首歌曲"
            }
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        prefsManager.clear()
        onLogoutComplete()
    }
}
