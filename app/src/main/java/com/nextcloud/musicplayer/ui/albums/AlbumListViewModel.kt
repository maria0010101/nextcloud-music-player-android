package com.nextcloud.musicplayer.ui.albums

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.data.sync.SyncLibraryWorker
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumListViewModel(
    val repository: MusicRepository,
    private val prefsManager: SecurePreferencesManager,
    private val context: Context? = null,
    val playerController: PlayerController? = null
) : ViewModel() {

    val rawAlbums: StateFlow<List<AlbumEntity>> = repository.getAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isFavoriteFilterActive = MutableStateFlow(false)
    val isFavoriteFilterActive: StateFlow<Boolean> = _isFavoriteFilterActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridMode = MutableStateFlow(true)
    val isGridMode: StateFlow<Boolean> = _isGridMode.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    private val _syncCompletedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val syncCompletedEvent: SharedFlow<String> = _syncCompletedEvent.asSharedFlow()

    private val _selectedFolder = MutableStateFlow(prefsManager.getSelectedMusicFolder())
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    val filteredAlbums: StateFlow<List<AlbumEntity>> = combine(
        rawAlbums,
        _searchQuery,
        _isFavoriteFilterActive
    ) { albums, query, isFavActive ->
        val base = if (isFavActive) albums.filter { it.isFavorite } else albums
        if (query.isBlank()) {
            base
        } else {
            base.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        context?.let { ctx ->
            observeSyncProgress(ctx)
        }
    }

    fun observeSyncProgress(ctx: Context) {
        val workManager = WorkManager.getInstance(ctx)
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SyncLibraryWorker.UNIQUE_WORK_NAME).collect { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        _isSyncing.value = true
                        val msg = workInfo.progress.getString(SyncLibraryWorker.KEY_PROGRESS_MESSAGE)
                        if (!msg.isNullOrBlank()) {
                            _syncMessage.value = msg
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val wasSyncing = _isSyncing.value
                        _isSyncing.value = false
                        val msg = workInfo.outputData.getString(SyncLibraryWorker.KEY_PROGRESS_MESSAGE)
                        if (!msg.isNullOrBlank()) {
                            _syncMessage.value = msg
                            if (wasSyncing) {
                                _syncCompletedEvent.tryEmit(msg)
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val wasSyncing = _isSyncing.value
                        _isSyncing.value = false
                        val msg = workInfo.outputData.getString(SyncLibraryWorker.KEY_PROGRESS_MESSAGE)
                            ?: workInfo.outputData.getString(SyncLibraryWorker.KEY_ERROR_MESSAGE)
                            ?: "同步失敗"
                        _syncMessage.value = msg
                        if (wasSyncing) {
                            _syncCompletedEvent.tryEmit(msg)
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        _isSyncing.value = false
                        _syncMessage.value = "同步已取消"
                    }
                    WorkInfo.State.BLOCKED -> {
                        _isSyncing.value = true
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleGridMode() {
        _isGridMode.value = !_isGridMode.value
    }

    fun toggleFavoriteFilter() {
        _isFavoriteFilterActive.value = !_isFavoriteFilterActive.value
    }

    fun toggleAlbumFavorite(albumId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            repository.updateAlbumFavorite(albumId, !currentFavorite)
        }
    }

    fun playFavoriteTracksShuffled(onNoTracks: () -> Unit) {
        viewModelScope.launch {
            val tracks = repository.getTracksFromFavoriteAlbums()
            if (tracks.isEmpty()) {
                onNoTracks()
            } else {
                val shuffled = tracks.shuffled()
                playerController?.playTracks(shuffled, startIndex = 0)
            }
        }
    }

    fun updateSelectedFolder(newFolder: String) {
        val clean = newFolder.trim().trim('/')
        _selectedFolder.value = clean
        prefsManager.saveSelectedMusicFolder(clean)
        syncLibrary()
    }

    fun syncLibrary(ctx: Context? = null) {
        val activeContext = ctx ?: context
        val targetFolder = _selectedFolder.value
        val display = if (targetFolder.isEmpty()) "根目錄" else "/$targetFolder"

        if (activeContext != null) {
            _isSyncing.value = true
            _syncMessage.value = "開始排程同步 $display..."
            SyncLibraryWorker.startSync(activeContext, targetFolder, isFullRescan = false)
            observeSyncProgress(activeContext)
        } else {
            if (_isSyncing.value) return
            _isSyncing.value = true
            _syncMessage.value = "開始快速同步 $display..."
            viewModelScope.launch {
                val result = repository.incrementalSync(
                    scopedFolder = targetFolder,
                    onProgress = { msg -> _syncMessage.value = msg }
                )
                _isSyncing.value = false
                val msg = if (result.isFailure) {
                    "同步失敗: ${result.exceptionOrNull()?.localizedMessage}"
                } else {
                    val res = result.getOrNull()
                    "快速同步完成！新增 ${res?.addedCount ?: 0}、更新 ${res?.modifiedCount ?: 0}、刪除 ${res?.deletedCount ?: 0}，共 ${res?.totalTracks ?: 0} 首"
                }
                _syncMessage.value = msg
                _syncCompletedEvent.tryEmit(msg)
            }
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        prefsManager.clear()
        onLogoutComplete()
    }
}
