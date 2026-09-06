package com.nextcloud.musicplayer.ui.detail

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nextcloud.musicplayer.data.download.AlbumDownloadWorker
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import com.nextcloud.musicplayer.data.repository.CoverManager
import com.nextcloud.musicplayer.data.repository.CoverSearchRepository
import com.nextcloud.musicplayer.data.repository.ItunesAlbumItem
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumDetailViewModel(
    private val albumId: String,
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val coverSearchRepository: CoverSearchRepository? = null,
    private val coverManager: CoverManager? = null,
    context: Context? = null
) : ViewModel() {

    private val TAG = "AlbumDetailViewModel"

    private val _album = MutableStateFlow<AlbumEntity?>(null)
    val album: StateFlow<AlbumEntity?> = _album.asStateFlow()

    val tracks: StateFlow<List<TrackEntity>> = repository.getTracksForAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _downloadStatus = MutableStateFlow<String?>(null)
    val downloadStatus: StateFlow<String?> = _downloadStatus.asStateFlow()

    // 模組 1 & 4：線上搜尋封面狀態
    private val _searchResults = MutableStateFlow<List<ItunesAlbumItem>>(emptyList())
    val searchResults: StateFlow<List<ItunesAlbumItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _isApplyingCover = MutableStateFlow(false)
    val isApplyingCover: StateFlow<Boolean> = _isApplyingCover.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAlbumById(albumId).collect {
                if (it != null) {
                    _album.value = it
                }
            }
        }

        context?.let { ctx ->
            observeDownloadProgress(ctx)
        }
    }

    fun observeDownloadProgress(context: Context) {
        val workManager = WorkManager.getInstance(context)
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow("download_album_$albumId").collect { workInfos ->
                val info = workInfos.firstOrNull { !it.state.isFinished || it.state == WorkInfo.State.RUNNING }
                if (info != null) {
                    val progress = info.progress.getInt(AlbumDownloadWorker.KEY_PROGRESS, 0)
                    val current = info.progress.getInt(AlbumDownloadWorker.KEY_CURRENT, 0)
                    val total = info.progress.getInt(AlbumDownloadWorker.KEY_TOTAL, 0)
                    _downloadStatus.value = if (total > 0) "$current/$total 下載中 ($progress%)" else "準備下載中..."
                } else {
                    val succeeded = workInfos.any { it.state == WorkInfo.State.SUCCEEDED }
                    if (succeeded) {
                        _downloadStatus.value = "已完成下載"
                        _album.value = repository.getAlbumById(albumId)
                    } else {
                        _downloadStatus.value = null
                    }
                }
            }
        }
    }

    fun startDownloadAlbum(context: Context) {
        val albumName = _album.value?.name ?: "音樂專輯"
        _downloadStatus.value = "已排入下載佇列..."
        AlbumDownloadWorker.startDownload(context, albumId, albumName)
        observeDownloadProgress(context)
    }

    fun playTrack(trackIndex: Int) {
        val currentTracks = tracks.value
        if (currentTracks.isNotEmpty() && trackIndex in currentTracks.indices) {
            playerController.playTracks(
                tracks = currentTracks,
                startIndex = trackIndex,
                coverUrl = _album.value?.coverUrl
            )
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val currentTracks = tracks.value
        if (currentTracks.isNotEmpty()) {
            val listToPlay = if (shuffle) currentTracks.shuffled() else currentTracks
            playerController.playTracks(
                tracks = listToPlay,
                startIndex = 0,
                coverUrl = _album.value?.coverUrl
            )
        }
    }

    /**
     * 模組 1：透過 iTunes Search API 搜尋候選專輯封面
     */
    fun searchCovers(query: String) {
        val repo = coverSearchRepository ?: return
        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            val result = repo.searchAlbums(query)
            _isSearching.value = false
            if (result.isSuccess) {
                _searchResults.value = result.getOrDefault(emptyList())
            } else {
                _searchError.value = result.exceptionOrNull()?.localizedMessage ?: "搜尋發生未知錯誤"
            }
        }
    }

    /**
     * 模組 3 & 4：雙分支套用封面
     *
     * @param item 選擇的 iTunes 專輯項目
     * @param toCloud true: 寫回 Nextcloud (WebDAV PUT); false: 僅限本機顯示 (App 私有目錄)
     * @param onResult 回呼函式 (成功與否, 回饋訊息)
     */
    fun applyCover(
        item: ItunesAlbumItem,
        toCloud: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val imageUrl = item.highResArtworkUrl ?: item.artworkUrl100
        if (imageUrl.isNullOrBlank()) {
            onResult(false, "此項目無有效封面圖檔網址")
            return
        }

        val currentAlbum = _album.value ?: return
        val searchRepo = coverSearchRepository
        val manager = coverManager

        if (searchRepo == null || manager == null) {
            onResult(false, "系統服務尚未初始化完成")
            return
        }

        viewModelScope.launch {
            _isApplyingCover.value = true
            try {
                // 1. 下載高解析度封面圖檔
                val downloadRes = searchRepo.downloadImageBytes(imageUrl)
                if (downloadRes.isFailure) {
                    _isApplyingCover.value = false
                    val err = downloadRes.exceptionOrNull()?.localizedMessage ?: "無法下載封面圖片"
                    onResult(false, "下載圖檔失敗: $err")
                    return@launch
                }

                val imageBytes = downloadRes.getOrThrow()

                // 2. 依選定模式寫入 (寫回雲端 or 僅限本機)
                val applyRes = if (toCloud) {
                    manager.applyCoverToCloud(albumId, currentAlbum.remotePath, imageBytes)
                } else {
                    manager.applyCoverToLocal(albumId, imageBytes)
                }

                _isApplyingCover.value = false

                if (applyRes.isSuccess) {
                    val newCoverUrl = applyRes.getOrThrow()
                    // 即時更新播放控制器的當前曲目封面 (若當前播放正好是此專輯)
                    playerController.updateCurrentTrackCover(albumId, newCoverUrl)
                    // 重新取得專輯資訊
                    _album.value = repository.getAlbumById(albumId)
                    val message = if (toCloud) "已上傳至 Nextcloud 並套用" else "已套用為本機專屬封面"
                    Log.d(TAG, "Cover successfully applied to album $albumId. Message: $message")
                    onResult(true, message)
                } else {
                    val err = applyRes.exceptionOrNull()?.localizedMessage ?: "套用失敗"
                    onResult(false, "更換封面失敗: $err")
                }
            } catch (e: Exception) {
                _isApplyingCover.value = false
                Log.e(TAG, "applyCover error", e)
                onResult(false, "處理異常: ${e.localizedMessage}")
            }
        }
    }
}
