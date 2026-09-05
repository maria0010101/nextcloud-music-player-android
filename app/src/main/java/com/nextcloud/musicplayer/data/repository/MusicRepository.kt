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
import java.math.BigInteger
import java.net.URLDecoder
import java.util.ArrayDeque

/**
 * 模組 2：檔案名稱自然排序器 (Natural Sort Order)
 * 確保 1, 2, ..., 9, 10 的順序，不使用字典序導致 1, 10, 2
 */
object NaturalOrderComparator : Comparator<String> {
    private val splitRegex = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")

    override fun compare(s1: String, s2: String): Int {
        val parts1 = s1.split(splitRegex)
        val parts2 = s2.split(splitRegex)
        val minLen = minOf(parts1.size, parts2.size)

        for (i in 0 until minLen) {
            val p1 = parts1[i]
            val p2 = parts2[i]
            val num1 = p1.toBigIntegerOrNull()
            val num2 = p2.toBigIntegerOrNull()

            val cmp = if (num1 != null && num2 != null) {
                num1.compareTo(num2)
            } else {
                p1.compareTo(p2, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return parts1.size.compareTo(parts2.size)
    }
}

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

    private data class ScanFolderNode(
        val folderUrl: String,
        val folderName: String,
        val parentName: String?,
        val inheritedCoverUrl: String?,
        val isTopLevel: Boolean
    )

    /**
     * 模組 2：資料夾層級名稱與封面繼承演算法
     * 1. 專輯名稱格式化：子資料夾格式化為 [母資料夾名稱] - [子資料夾名稱]，頂層資料夾則直接顯示資料夾名。
     * 2. 封面繼承回退：本目錄封面優先 > 自動向上查找母資料夾封面 > 預設 Placeholder。
     * 3. 曲目自然排序：依照檔案名稱進行自然排序。
     * 4. 母目錄過濾：本身無音訊的母目錄禁止作為獨立專輯。
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
            val topFolderName = if (cleanFolder.isEmpty()) "音樂檔案" else cleanFolder.substringAfterLast('/')

            onProgress("正在連線 WebDAV: /$cleanFolder...")
            Log.d(TAG, "Starting scoped scan in: $targetBaseUrl")

            val queue = ArrayDeque<ScanFolderNode>()
            queue.add(
                ScanFolderNode(
                    folderUrl = targetBaseUrl,
                    folderName = topFolderName,
                    parentName = null,
                    inheritedCoverUrl = null,
                    isTopLevel = true
                )
            )

            val discoveredAlbums = mutableListOf<AlbumEntity>()
            val discoveredTracks = mutableListOf<TrackEntity>()
            var folderCount = 0

            while (!queue.isEmpty()) {
                val node = queue.poll() ?: break
                folderCount++

                onProgress("掃描資料夾 ($folderCount): ${node.folderName}")

                val itemsResult = webDavClient.listFolder(node.folderUrl, depth = 1)
                if (itemsResult.isFailure) {
                    Log.w(TAG, "無法存取資料夾: ${node.folderUrl}")
                    continue
                }

                val items = itemsResult.getOrDefault(emptyList())
                val directAudios = items.filter { it.isAudioFile }
                val directSubFolders = items.filter { it.isCollection && !isSamePath(it.href, node.folderUrl) }

                // 封面優先順序判定：子目錄優先 > 繼承母資料夾封面
                val localCover = findCoverImage(items)
                val effectiveCoverUrl = localCover?.let { webDavClient.resolveFullUrl(it.href) }
                    ?: node.inheritedCoverUrl

                // 判定是否為含有音訊的專輯目錄
                if (directAudios.isNotEmpty()) {
                    // 命名規則：若音訊在子資料夾內，格式化為 [母資料夾名稱] - [子資料夾名稱]
                    val albumDisplayName = if (node.isTopLevel || node.parentName.isNullOrBlank()) {
                        node.folderName
                    } else {
                        "${node.parentName} - ${node.folderName}"
                    }

                    // 強制依檔案名稱進行自然排序
                    val sortedAudios = directAudios.sortedWith { a, b ->
                        val nameA = a.displayName.ifBlank { a.href.substringAfterLast('/') }
                        val nameB = b.displayName.ifBlank { b.href.substringAfterLast('/') }
                        NaturalOrderComparator.compare(nameA, nameB)
                    }

                    val albumId = normalizePath(node.folderUrl)
                    val album = AlbumEntity(
                        id = albumId,
                        name = albumDisplayName,
                        remotePath = node.folderUrl,
                        coverUrl = effectiveCoverUrl,
                        trackCount = sortedAudios.size
                    )
                    discoveredAlbums.add(album)

                    sortedAudios.forEachIndexed { index, audioItem ->
                        discoveredTracks.add(createTrackEntity(audioItem, albumId, index + 1, effectiveCoverUrl))
                    }
                    Log.d(TAG, "已建立專輯: [$albumDisplayName], 封面: $effectiveCoverUrl, 曲目數: ${sortedAudios.size}")
                } else {
                    Log.d(TAG, "資料夾 [${node.folderName}] 內無直接音訊，不建立獨立專輯，向子目錄傳遞封面")
                }

                // 將子資料夾加入走訪佇列，並傳遞當前目錄名作為 parentName、以及當前封面作為 inheritedCoverUrl
                for (subFolder in directSubFolders) {
                    val subFolderName = subFolder.displayName.ifBlank {
                        subFolder.href.trimEnd('/').substringAfterLast('/')
                    }
                    queue.add(
                        ScanFolderNode(
                            folderUrl = subFolder.href,
                            folderName = subFolderName,
                            parentName = node.folderName,
                            inheritedCoverUrl = effectiveCoverUrl,
                            isTopLevel = false
                        )
                    )
                }
            }

            onProgress("正在存入快取資料庫 (${discoveredAlbums.size} 張專輯、${discoveredTracks.size} 首歌曲)...")

            val updatedTracks = checkExistingLocalFiles(discoveredTracks)

            database.albumDao().clearAlbums()
            database.trackDao().clearTracks()
            database.albumDao().insertAlbums(discoveredAlbums)
            database.trackDao().insertTracks(updatedTracks)

            onProgress("掃描完成！發現 ${discoveredAlbums.size} 張有效專輯、共 ${discoveredTracks.size} 首歌曲")
            Result.success(discoveredTracks.size)
        } catch (e: Exception) {
            Log.e(TAG, "掃描過程異常", e)
            Result.failure(e)
        }
    }

    private fun checkExistingLocalFiles(tracks: List<TrackEntity>): List<TrackEntity> {
        val baseDir = context?.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return tracks
        return tracks.map { track ->
            val safeAlbum = track.albumId.trimEnd('/').substringAfterLast('/')
            val safeFile = "${track.trackNumber.toString().padStart(2, '0')} - ${track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.${track.format.lowercase()}"
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
