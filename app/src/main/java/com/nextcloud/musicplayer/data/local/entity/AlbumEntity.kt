package com.nextcloud.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String, // Folder remote path
    val name: String,
    val remotePath: String,
    val coverUrl: String?,
    val isCustomLocalCover: Boolean = false,
    val trackCount: Int = 0,
    val isDownloaded: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val lastModified: String? = null
) {
    val coverUri: String? get() = coverUrl
}

