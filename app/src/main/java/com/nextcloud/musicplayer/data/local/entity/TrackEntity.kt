package com.nextcloud.musicplayer.data.local.entity

import androidx.room.Entity
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
    val format: String = "MP3",
    val coverUrl: String? = null,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null
) {
    val localUri: String? get() = localFilePath

    /**
     * 模組 4：離線播放本機路徑索引
     * 若本機檔案存在且已下載完成，優先使用本機 file:// 或 SAF content:// URI，實現完全離線播放
     */
    val playableUri: String
        get() {
            if (isDownloaded && !localFilePath.isNullOrBlank()) {
                if (localFilePath.startsWith("content://")) {
                    return localFilePath
                }
                val file = java.io.File(localFilePath)
                if (file.exists() && file.length() > 0) {
                    return android.net.Uri.fromFile(file).toString()
                }
            }
            return streamUrl
        }
}
