package com.nextcloud.musicplayer.data.repository

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
import java.net.URLDecoder

class MusicRepository(
    private val webDavClient: NextcloudWebDavClient,
    private val database: AppDatabase,
    private val prefsManager: SecurePreferencesManager
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
     * 単層瀏覽資料夾目錄 (Depth: 1)，專供目錄瀏覽選取器使用
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
            // 僅回傳資料夾，並過濾掉當前根節點
            val directories = items.filter { it.isCollection && !isSamePath(it.href, targetUrl) }
            Result.success(directories)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing directories", e)
            Result.failure(e)
        }
    }

    /**
     * 局限掃描：僅針對使用者指定的目錄 (例如 /Music) 進行音訊與封面掃描
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

            onProgress("正在連線 WebDAV: $folderDisplayName")
            Log.d(TAG, "Starting scoped scan in: $targetBaseUrl")

            val targetItemsResult = webDavClient.listFolder(targetBaseUrl, depth = 1)
            if (targetItemsResult.isFailure) {
                val err = targetItemsResult.exceptionOrNull() ?: Exception("無法存取指定資料夾: $folderDisplayName")
                Log.e(TAG, "Failed to list folder $targetBaseUrl", err)
                return@withContext Result.failure(err)
            }

            val targetItems = targetItemsResult.getOrDefault(emptyList())
            val subFolders = targetItems.filter { it.isCollection && !isSamePath(it.href, targetBaseUrl) }
            val directAudios = targetItems.filter { it.isAudioFile }

            val discoveredAlbums = mutableListOf<AlbumEntity>()
            val discoveredTracks = mutableListOf<TrackEntity>()

            // 1. 若選定目錄下直接包含音訊檔，將其建立為該資料夾專屬專輯
            if (directAudios.isNotEmpty()) {
                val directCover = findCoverImage(targetItems)
                val directAlbumId = normalizePath(targetBaseUrl)
                val directAlbum = AlbumEntity(
                    id = directAlbumId,
                    name = if (cleanFolder.isEmpty()) "音樂檔案" else cleanFolder.substringAfterLast('/'),
                    remotePath = targetBaseUrl,
                    coverUrl = directCover?.let { webDavClient.resolveFullUrl(it.href) },
                    trackCount = directAudios.size
                )
                discoveredAlbums.add(directAlbum)

                directAudios.forEachIndexed { index, audioItem ->
                    discoveredTracks.add(createTrackEntity(audioItem, directAlbumId, index + 1))
                }
            }

            // 2. 掃描各子資料夾 (視為個別專輯)
            for ((index, folder) in subFolders.withIndex()) {
                val folderName = folder.displayName.ifBlank {
                    folder.href.trimEnd('/').substringAfterLast('/')
                }
                onProgress("掃描專輯 (${index + 1}/${subFolders.size}): $folderName")

                val folderContentsResult = webDavClient.listFolder(folder.href, depth = 1)
                if (folderContentsResult.isFailure) continue

                val items = folderContentsResult.getOrDefault(emptyList())
                val audioFiles = items.filter { it.isAudioFile }
                val nestedFolders = items.filter { it.isCollection && !isSamePath(it.href, folder.href) }

                if (audioFiles.isNotEmpty()) {
                    val cover = findCoverImage(items)
                    val albumId = normalizePath(folder.href)
                    val album = AlbumEntity(
                        id = albumId,
                        name = folderName,
                        remotePath = folder.href,
                        coverUrl = cover?.let { webDavClient.resolveFullUrl(it.href) },
                        trackCount = audioFiles.size
                    )
                    discoveredAlbums.add(album)

                    audioFiles.forEachIndexed { trackIdx, audioItem ->
                        discoveredTracks.add(createTrackEntity(audioItem, albumId, trackIdx + 1))
                    }
                }

                // 若子資料夾內包含第二層資料夾 (如 歌手/專輯)，再多讀取一層
                for (nested in nestedFolders) {
                    val nestedName = nested.displayName.ifBlank {
                        nested.href.trimEnd('/').substringAfterLast('/')
                    }
                    val nestedResult = webDavClient.listFolder(nested.href, depth = 1)
                    if (nestedResult.isFailure) continue

                    val nestedItems = nestedResult.getOrDefault(emptyList())
                    val nestedAudios = nestedItems.filter { it.isAudioFile }
                    if (nestedAudios.isNotEmpty()) {
                        val nestedCover = findCoverImage(nestedItems) ?: findCoverImage(items)
                        val nestedAlbumId = normalizePath(nested.href)
                        val album = AlbumEntity(
                            id = nestedAlbumId,
                            name = "$folderName - $nestedName",
                            remotePath = nested.href,
                            coverUrl = nestedCover?.let { webDavClient.resolveFullUrl(it.href) },
                            trackCount = nestedAudios.size
                        )
                        discoveredAlbums.add(album)

                        nestedAudios.forEachIndexed { trackIdx, audioItem ->
                            discoveredTracks.add(createTrackEntity(audioItem, nestedAlbumId, trackIdx + 1))
                        }
                    }
                }
            }

            onProgress("正在存入快取資料庫 (${discoveredAlbums.size} 張專輯、${discoveredTracks.size} 首歌曲)...")

            // 儲存至 Room
            database.albumDao().clearAlbums()
            database.trackDao().clearTracks()
            database.albumDao().insertAlbums(discoveredAlbums)
            database.trackDao().insertTracks(discoveredTracks)

            onProgress("掃描完成！共發現 ${discoveredTracks.size} 首歌曲")
            Result.success(discoveredTracks.size)
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed with exception", e)
            Result.failure(e)
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

    private fun createTrackEntity(audioItem: WebDavItem, albumId: String, trackNumber: Int): TrackEntity {
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
            format = audioItem.audioFormat
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
