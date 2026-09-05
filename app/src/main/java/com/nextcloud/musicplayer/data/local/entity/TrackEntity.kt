package com.nextcloud.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["albumId"])]
)
data class TrackEntity(
    @PrimaryKey val id: String, // File remote path or href
    val albumId: String,
    val title: String,
    val remotePath: String,
    val streamUrl: String,
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val mimeType: String = "audio/mpeg",
    val trackNumber: Int = 0,
    val format: String = "MP3"
)
