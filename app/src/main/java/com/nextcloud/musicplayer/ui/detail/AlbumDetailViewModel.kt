package com.nextcloud.musicplayer.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nextcloud.musicplayer.data.download.AlbumDownloadWorker
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
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
    context: Context? = null
) : ViewModel() {

    private val _album = MutableStateFlow<AlbumEntity?>(null)
    val album: StateFlow<AlbumEntity?> = _album.asStateFlow()

    val tracks: StateFlow<List<TrackEntity>> = repository.getTracksForAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _downloadStatus = MutableStateFlow<String?>(null)
    val downloadStatus: StateFlow<String?> = _downloadStatus.asStateFlow()

    init {
        viewModelScope.launch {
            _album.value = repository.getAlbumById(albumId)
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
}
