package com.nextcloud.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String, // Folder remote path
    val name: String,
    val remotePath: String,
    val coverUrl: String?,
    val trackCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
