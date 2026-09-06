package com.nextcloud.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY trackNumber ASC, title ASC")
    fun getTracksForAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY trackNumber ASC, title ASC")
    fun getTracksForAlbumSync(albumId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>): List<Long>

    @Query("UPDATE tracks SET durationMs = :durationMs WHERE id = :id OR streamUrl = :id")
    suspend fun updateDuration(id: String, durationMs: Long): Int

    @Query("UPDATE tracks SET isDownloaded = 1, localFilePath = :localFilePath WHERE id = :trackId")
    suspend fun markTrackDownloaded(trackId: String, localFilePath: String): Int

    @Query("UPDATE tracks SET coverUrl = :coverUrl WHERE albumId = :albumId")
    suspend fun updateCoverForAlbumTracks(albumId: String, coverUrl: String?): Int

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteTracksByAlbum(albumId: String): Int

    @Query("DELETE FROM tracks")
    suspend fun clearTracks(): Int
}
