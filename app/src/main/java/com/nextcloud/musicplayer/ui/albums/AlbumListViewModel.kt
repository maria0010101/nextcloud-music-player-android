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
    private val repository: MusicRepository,
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

    fun syncLibrary() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _syncMessage.value = "開始連線 WebDAV 進行掃描..."

        viewModelScope.launch {
            val result = repository.scanMusicLibrary(
                onProgress = { msg -> _syncMessage.value = msg }
            )
            _isSyncing.value = false
            if (result.isFailure) {
                _syncMessage.value = "掃描失敗: ${result.exceptionOrNull()?.localizedMessage}"
            } else {
                _syncMessage.value = "掃描完成！"
            }
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        prefsManager.clear()
        onLogoutComplete()
    }
}
