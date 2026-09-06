package com.nextcloud.musicplayer.data.repository

import android.content.Context
import android.util.Log
import coil.imageLoader
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class CoverManager(
    private val context: Context,
    private val webDavClient: NextcloudWebDavClient,
    private val database: AppDatabase
) {
    private val TAG = "CoverManager"

    /**
     * 分支 A：寫回雲端 (同步至 Nextcloud)
     * 1. 透過 WebDAV PUT 上傳圖片至該專輯目錄命名為 cover.jpg
     * 2. 更新 Room: isCustomLocalCover = false, coverUrl 指向遠端 cover.jpg
     * 3. 清除 Coil 圖片快取以確保即時重新載入
     */
    suspend fun applyCoverToCloud(
        albumId: String,
        remoteFolderPath: String,
        imageBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading cover to cloud for album: $albumId, folder: $remoteFolderPath")
            val uploadResult = webDavClient.uploadAlbumCover(remoteFolderPath, imageBytes)
            if (uploadResult.isFailure) {
                return@withContext Result.failure(
                    uploadResult.exceptionOrNull() ?: Exception("上傳封面至 Nextcloud 失敗")
                )
            }

            // 取得遠端封面完整 URL，加上時間戳記以防止快取舊圖
            val cleanRemote = remoteFolderPath.trimEnd('/')
            val resolvedUrl = webDavClient.resolveFullUrl("$cleanRemote/cover.jpg")
            val timestampedUrl = if (resolvedUrl.contains("?")) {
                "$resolvedUrl&t=${System.currentTimeMillis()}"
            } else {
                "$resolvedUrl?t=${System.currentTimeMillis()}"
            }

            // 更新 Room 資料庫
            database.albumDao().updateAlbumCover(
                albumId = albumId,
                coverUrl = timestampedUrl,
                isCustomLocalCover = false
            )
            database.trackDao().updateCoverForAlbumTracks(
                albumId = albumId,
                coverUrl = timestampedUrl
            )

            // 清除 Coil 快取
            clearCoilCache()

            Log.d(TAG, "Successfully applied cloud cover: $timestampedUrl")
            Result.success(timestampedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "applyCoverToCloud failed", e)
            Result.failure(e)
        }
    }

    /**
     * 分支 B：僅限本機顯示 (不更動雲端)
     * 1. 完全不發送 WebDAV 寫入請求
     * 2. 將圖片保存至 App 私有目錄 context.filesDir/custom_covers/<albumId_md5>.jpg
     * 3. 更新 Room: isCustomLocalCover = true, coverUrl 指向本地私有圖檔路徑
     * 4. 清除 Coil 圖片快取以確保即時重新載入
     */
    suspend fun applyCoverToLocal(
        albumId: String,
        imageBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Saving cover locally for album: $albumId")
            val customCoversDir = File(context.filesDir, "custom_covers")
            if (!customCoversDir.exists()) {
                customCoversDir.mkdirs()
            }

            val safeId = md5(albumId)
            val coverFile = File(customCoversDir, "$safeId.jpg")
            coverFile.writeBytes(imageBytes)

            val localFilePath = coverFile.absolutePath

            // 更新 Room 資料庫
            database.albumDao().updateAlbumCover(
                albumId = albumId,
                coverUrl = localFilePath,
                isCustomLocalCover = true
            )
            database.trackDao().updateCoverForAlbumTracks(
                albumId = albumId,
                coverUrl = localFilePath
            )

            // 清除 Coil 快取
            clearCoilCache()

            Log.d(TAG, "Successfully applied local cover: $localFilePath")
            Result.success(localFilePath)
        } catch (e: Exception) {
            Log.e(TAG, "applyCoverToLocal failed", e)
            Result.failure(e)
        }
    }

    /**
     * 清除 Coil 記憶體與磁碟快取
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCoilCache() {
        try {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing Coil cache", e)
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
