package com.nextcloud.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun getAllAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteAlbumsFlow(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums")
    suspend fun getAllAlbumsList(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun getAlbumById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun observeAlbumById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun getAlbumByIdSync(id: String): AlbumEntity?

    @Query("UPDATE albums SET isFavorite = :isFavorite WHERE id = :albumId")
    suspend fun updateFavoriteStatus(albumId: String, isFavorite: Boolean): Int

    @Query("SELECT tracks.* FROM tracks INNER JOIN albums ON tracks.albumId = albums.id WHERE albums.isFavorite = 1 ORDER BY albums.name ASC, tracks.trackNumber ASC, tracks.title ASC")
    suspend fun getTracksFromFavoriteAlbums(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>): List<Long>

    @Query("UPDATE albums SET coverUrl = :coverUrl, isCustomLocalCover = :isCustomLocalCover, updatedAt = :updatedAt WHERE id = :albumId")
    suspend fun updateAlbumCover(albumId: String, coverUrl: String?, isCustomLocalCover: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE albums SET isDownloaded = :isDownloaded WHERE id = :albumId")
    suspend fun markAlbumDownloaded(albumId: String, isDownloaded: Boolean = true): Int

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteAlbum(id: String): Int

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteAlbumsByIds(ids: List<String>): Int

    @Query("DELETE FROM albums WHERE remotePath IN (:remotePaths)")
    suspend fun deleteAlbumsByPaths(remotePaths: List<String>): Int

    @Query("DELETE FROM albums")
    suspend fun clearAlbums(): Int
}
