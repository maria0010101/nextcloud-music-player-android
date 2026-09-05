package com.nextcloud.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY title ASC")
    fun getTracksForAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>): List<Long>

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteTracksByAlbum(albumId: String): Int

    @Query("DELETE FROM tracks")
    suspend fun clearTracks(): Int
}
