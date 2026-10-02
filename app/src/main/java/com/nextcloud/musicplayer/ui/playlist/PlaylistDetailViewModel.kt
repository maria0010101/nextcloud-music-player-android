package com.nextcloud.musicplayer.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.data.local.entity.PlaylistEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    val playlistId: Long,
    val repository: MusicRepository,
    val playerController: PlayerController?
) : ViewModel() {

    val playlist: StateFlow<PlaylistEntity?> = repository.getPlaylistById(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val tracks: StateFlow<List<TrackEntity>> = repository.getTracksForPlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun playTrack(startIndex: Int) {
        val currentTracks = tracks.value
        if (currentTracks.isNotEmpty()) {
            playerController?.playTracks(currentTracks, startIndex = startIndex)
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val currentTracks = tracks.value
        if (currentTracks.isEmpty()) return
        val listToPlay = if (shuffle) currentTracks.shuffled() else currentTracks
        playerController?.playTracks(listToPlay, startIndex = 0)
    }

    fun appendToQueue() {
        val currentTracks = tracks.value
        if (currentTracks.isNotEmpty()) {
            playerController?.appendTracks(currentTracks)
        }
    }

    fun removeTrack(trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun renamePlaylist(newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
        }
    }
}
