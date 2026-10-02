package com.nextcloud.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.nextcloud.musicplayer.data.local.entity.PlaylistEntity
import com.nextcloud.musicplayer.data.local.entity.PlaylistTrackEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithTrackCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("""
        SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(pt.id) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON p.id = pt.playlistId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
    """)
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithTrackCount>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun getPlaylistById(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistByIdDirect(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity): Int

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long): Int

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteTracksForPlaylist(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(tracks: List<PlaylistTrackEntity>): List<Long>

    @Query("""
        SELECT tracks.* FROM tracks
        INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId
        WHERE playlist_tracks.playlistId = :playlistId
        ORDER BY playlist_tracks.orderIndex ASC, playlist_tracks.id ASC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query("""
        SELECT tracks.* FROM tracks
        INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId
        WHERE playlist_tracks.playlistId = :playlistId
        ORDER BY playlist_tracks.orderIndex ASC, playlist_tracks.id ASC
    """)
    suspend fun getTracksForPlaylistDirect(playlistId: Long): List<TrackEntity>

    @Query("SELECT MAX(orderIndex) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxOrderIndex(playlistId: Long): Int?

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String): Int

    @Query("DELETE FROM playlist_tracks WHERE id = :recordId")
    suspend fun removeTrackByRecordId(recordId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCount(playlistId: Long): Int
}
