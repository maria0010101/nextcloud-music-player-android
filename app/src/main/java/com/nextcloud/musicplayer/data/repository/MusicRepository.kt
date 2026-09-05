package com.nextcloud.musicplayer.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.network.WebDavItem
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.local.AppDatabase
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.ArrayDeque

class MusicRepository(
    private val webDavClient: NextcloudWebDavClient,
    val database: AppDatabase,
    private val prefsManager: SecurePreferencesManager,
    private val context: Context? = null
) {

    private val TAG = "MusicRepository"

    fun getAlbums(): Flow<List<AlbumEntity>> = database.albumDao().getAllAlbums()

    fun getTracksForAlbum(albumId: String): Flow<List<TrackEntity>> =
        database.trackDao().getTracksForAlbum(albumId)

    suspend fun getAlbumById(albumId: String): AlbumEntity? =
        database.albumDao().getAlbumById(albumId)

    suspend fun updateTrackDuration(trackIdOrUrl: String, durationMs: Long) = withContext(Dispatchers.IO) {
        if (durationMs > 0) {
            database.trackDao().updateDuration(trackIdOrUrl, durationMs)
        }
    }

    /**
     * 單層瀏覽資料夾目錄 (Depth: 1)，專供目錄選取對話框使用
     */
    suspend fun listDirectories(folderPath: String): Result<List<WebDavItem>> = withContext(Dispatchers.IO) {
        try {
            val rootHref = prefsManager.getWebDavBaseUrl()
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 帳號憑證"))

            val cleanRelative = folderPath.trim().trim('/')
            val targetUrl = if (cleanRelative.isEmpty()) rootHref else "$rootHref/$cleanRelative"

            Log.d(TAG, "Listing directory: $targetUrl")
            val result = webDavClient.listFolder(targetUrl, depth = 1)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("無法載入目錄清單"))
            }

            val items = result.getOrDefault(emptyList())
            val directories = items.filter { it.isCollection && !isSamePath(it.href, targetUrl) }
            Result.success(directories)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing directories", e)
            Result.failure(e)
        }
    }

    /**
     * 模組 3：資料夾層級掃描邏輯（葉子/含音訊目錄判定）
     * 規則：
     * 1. 若母資料夾僅包含子資料夾、本身沒有任何音訊檔案，禁止將該母資料夾列為獨立專輯。
     * 2. 以「包含音訊檔案的資料夾」作為最小單位識別為「一張專輯」。
     * 3. 巢狀層級相容：遞迴遍歷所有子資料夾，只要子資料夾內含有音訊檔案，該子資料夾即成為獨立專輯。
     */
    suspend fun scanMusicLibrary(
        scopedFolder: String = "",
        onProgress: (message: String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val rootHref = prefsManager.getWebDavBaseUrl()
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 帳號憑證"))

            val cleanFolder = scopedFolder.trim().trim('/')
            val targetBaseUrl = if (cleanFolder.isEmpty()) rootHref else "$rootHref/$cleanFolder"
            val folderDisplayName = if (cleanFolder.isEmpty()) "根目錄" else "/$cleanFolder"

            onProgress("正在連線 WebDAV: $folderDisplayName...")
            Log.d(TAG, "Starting scoped scan in: $targetBaseUrl")

            val folderQueue = ArrayDeque<String>()
            folderQueue.add(targetBaseUrl)

            val discoveredAlbums = mutableListOf<AlbumEntity>()
            val discoveredTracks = mutableListOf<TrackEntity>()
            var scannedFolderCount = 0

            while (!folderQueue.isEmpty()) {
                val currentFolderUrl = folderQueue.poll() ?: break
                scannedFolderCount++

                val currentFolderName = try {
                    URLDecoder.decode(currentFolderUrl, "UTF-8").trimEnd('/').substringAfterLast('/')
                } catch (_: Exception) {
                    currentFolderUrl.trimEnd('/').substringAfterLast('/')
                }

                onProgress("掃描資料夾 ($scannedFolderCount): $currentFolderName")

                val itemsResult = webDavClient.listFolder(currentFolderUrl, depth = 1)
                if (itemsResult.isFailure) {
                    Log.w(TAG, "Skipping inaccessible folder: $currentFolderUrl")
                    continue
                }

                val items = itemsResult.getOrDefault(emptyList())
                val directAudios = items.filter { it.isAudioFile }
                val directSubFolders = items.filter { it.isCollection && !isSamePath(it.href, currentFolderUrl) }

                // 核心規則：檢查當前資料夾本身是否「直接包含音訊檔案」
                if (directAudios.isNotEmpty()) {
                    // 本身含有音訊 -> 識別為一張獨立專輯
                    val cover = findCoverImage(items)
                    val coverFullUrl = cover?.let { webDavClient.resolveFullUrl(it.href) }
                    val albumId = normalizePath(currentFolderUrl)

                    val album = AlbumEntity(
                        id = albumId,
                        name = currentFolderName.ifBlank { "音樂專輯" },
                        remotePath = currentFolderUrl,
                        coverUrl = coverFullUrl,
                        trackCount = directAudios.size
                    )
                    discoveredAlbums.add(album)

                    directAudios.forEachIndexed { index, audioItem ->
                        discoveredTracks.add(createTrackEntity(audioItem, albumId, index + 1, coverFullUrl))
                    }
                    Log.d(TAG, "發現專輯 [${album.name}] (曲目數: ${directAudios.size})")
                } else {
                    // 本身沒有任何音訊檔案 -> 判定為純容器母目錄，嚴格禁止加入專輯列表！
                    Log.d(TAG, "母資料夾 [$currentFolderName] 內無直接音訊，排除為專輯，繼續搜尋子資料夾")
                }

                // 將所有子資料夾加入佇列，進一步深入掃描
                for (subFolder in directSubFolders) {
                    folderQueue.add(subFolder.href)
                }
            }

            onProgress("正在存入快取資料庫 (${discoveredAlbums.size} 張專輯、${discoveredTracks.size} 首歌曲)...")

            // 檢查本機離線檔案是否存在，若存在則標記 isDownloaded
            val updatedTracks = checkExistingLocalFiles(discoveredTracks)

            // 儲存至 Room
            database.albumDao().clearAlbums()
            database.trackDao().clearTracks()
            database.albumDao().insertAlbums(discoveredAlbums)
            database.trackDao().insertTracks(updatedTracks)

            onProgress("掃描完成！發現 ${discoveredAlbums.size} 張有效專輯、共 ${discoveredTracks.size} 首歌曲")
            Result.success(discoveredTracks.size)
        } catch (e: Exception) {
            Log.e(TAG, "掃描過程發生錯誤", e)
            Result.failure(e)
        }
    }

    private fun checkExistingLocalFiles(tracks: List<TrackEntity>): List<TrackEntity> {
        val baseDir = context?.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return tracks
        return tracks.map { track ->
            val safeAlbum = track.albumId.trimEnd('/').substringAfterLast('/')
            val safeFile = track.title.replace("/", "_") + "." + track.format.lowercase()
            val localFile = File(baseDir, "albums/$safeAlbum/$safeFile")
            if (localFile.exists() && localFile.length() > 0) {
                track.copy(isDownloaded = true, localFilePath = localFile.absolutePath)
            } else {
                track
            }
        }
    }

    private fun findCoverImage(items: List<WebDavItem>): WebDavItem? {
        val images = items.filter { it.isImageFile }
        if (images.isEmpty()) return null

        val preferredNames = listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "album.jpg", "front.jpg")
        for (preferred in preferredNames) {
            val match = images.find { it.displayName.equals(preferred, ignoreCase = true) }
            if (match != null) return match
        }

        return images.firstOrNull()
    }

    private fun createTrackEntity(
        audioItem: WebDavItem,
        albumId: String,
        trackNumber: Int,
        coverUrl: String?
    ): TrackEntity {
        val rawTitle = audioItem.displayName.ifBlank {
            audioItem.href.trimEnd('/').substringAfterLast('/')
        }
        val cleanTitle = rawTitle.substringBeforeLast('.')
            .replaceFirst(Regex("^[0-9]+[.\\s\\-_]+"), "")

        val streamUrl = webDavClient.resolveFullUrl(audioItem.href)

        return TrackEntity(
            id = audioItem.href,
            albumId = albumId,
            title = cleanTitle.ifBlank { rawTitle },
            remotePath = audioItem.href,
            streamUrl = streamUrl,
            durationMs = 0L,
            fileSize = audioItem.contentLength,
            mimeType = audioItem.contentType ?: "audio/mpeg",
            trackNumber = trackNumber,
            format = audioItem.audioFormat,
            coverUrl = coverUrl,
            isDownloaded = false,
            localFilePath = null
        )
    }

    private fun normalizePath(path: String): String {
        return try {
            URLDecoder.decode(path, "UTF-8").trimEnd('/')
        } catch (e: Exception) {
            path.trimEnd('/')
        }
    }

    private fun isSamePath(path1: String, path2: String): Boolean {
        val p1 = normalizePath(path1)
        val p2 = normalizePath(path2)
        return p1.equals(p2, ignoreCase = true) || p1.endsWith(p2) || p2.endsWith(p1)
    }
}
