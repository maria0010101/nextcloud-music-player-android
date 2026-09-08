package com.nextcloud.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun getAllAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums")
    suspend fun getAllAlbumsList(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun getAlbumById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun observeAlbumById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    fun getAlbumByIdSync(id: String): AlbumEntity?

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
