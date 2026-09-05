package com.nextcloud.musicplayer.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val playerController: PlayerController
) : ViewModel() {

    private val _album = MutableStateFlow<AlbumEntity?>(null)
    val album: StateFlow<AlbumEntity?> = _album.asStateFlow()

    val tracks: StateFlow<List<TrackEntity>> = repository.getTracksForAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            _album.value = repository.getAlbumById(albumId)
        }
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
