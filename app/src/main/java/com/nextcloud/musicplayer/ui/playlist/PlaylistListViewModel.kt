package com.nextcloud.musicplayer.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.data.local.dao.PlaylistWithTrackCount
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlayerController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistListViewModel(
    val repository: MusicRepository,
    val playerController: PlayerController?
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistWithTrackCount>> = repository.getAllPlaylistsWithCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createPlaylist(name: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = repository.createPlaylist(name)
            onCreated?.invoke(id)
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(id, newName)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
        }
    }

    fun playPlaylist(id: Long, shuffle: Boolean = false, onEmpty: (() -> Unit)? = null) {
        viewModelScope.launch {
            val tracks = repository.getTracksForPlaylistDirect(id)
            if (tracks.isEmpty()) {
                onEmpty?.invoke()
            } else {
                val list = if (shuffle) tracks.shuffled() else tracks
                playerController?.playTracks(list, startIndex = 0)
            }
        }
    }
}
