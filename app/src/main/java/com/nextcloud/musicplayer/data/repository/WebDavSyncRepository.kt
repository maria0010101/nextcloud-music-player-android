package com.nextcloud.musicplayer.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.network.WebDavItem
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import com.nextcloud.musicplayer.data.local.AppDatabase
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import com.nextcloud.musicplayer.playback.ArtworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * 同步結果數據
 */
data class SyncResult(
    val addedCount: Int = 0,
    val modifiedCount: Int = 0,
    val deletedCount: Int = 0,
    val unchangedCount: Int = 0,
    val totalTracks: Int = 0,
    val totalAlbums: Int = 0
)

/**
 * 模組：WebDAV 音樂庫增量同步與差分掃描器 (Incremental Diff Sync)
 */
class WebDavSyncRepository(
    private val webDavClient: NextcloudWebDavClient,
    private val database: AppDatabase,
    private val prefsManager: SecurePreferencesManager,
    private val context: Context? = null,
    private val settingsDataStore: AppSettingsDataStore? = null
) {

    private data class FolderScanNode(
        val folderUrl: String,
        val relativeSegments: List<String>,
        val inheritedCoverUrl: String?,
        val knownEtag: String? = null,
        val knownLastModified: String? = null
    )

    /**
     * 快速同步（增量更新）：
     * 1. 遠端與本機目錄清單對齊
     * 2. 處理已刪除資料夾 (Batch prune)
     * 3. 處理新增與異動資料夾 (略過未變更目錄)
     */
    suspend fun incrementalSync(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        try {
            val rootHref = prefsManager.getWebDavBaseUrl()
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 帳號憑證"))

            val cleanFolder = scopedFolder.trim().trim('/')
            val targetBaseUrl = if (cleanFolder.isEmpty()) rootHref.trimEnd('/') else "${rootHref.trimEnd('/')}/$cleanFolder"
            val targetNormalizedBase = normalizePath(targetBaseUrl)

            val effectiveLevels = selectedLevels
                ?: settingsDataStore?.albumNameLevels?.firstOrNull()
                ?: AppSettingsDataStore.DEFAULT_ALBUM_NAME_LEVELS

            onProgress("正在比對目錄差異...")
            Log.d(TAG, "Starting incremental sync in: $targetBaseUrl")

            // 1. 第一步：取得本機目錄清單
            val allLocalAlbums = database.albumDao().getAllAlbumsList()
            val localAlbumsMap = allLocalAlbums
                .filter { album ->
                    val norm = normalizePath(album.remotePath)
                    if (targetNormalizedBase == "/") {
                        true
                    } else {
                        norm.equals(targetNormalizedBase, ignoreCase = true) ||
                        norm.startsWith("$targetNormalizedBase/", ignoreCase = true)
                    }
                }
                .associateBy { normalizePath(it.remotePath) }
            val localAlbumPaths = localAlbumsMap.keys.toSet()

            // 清洗現有資料庫中可能殘留的前綴髒資料 (public.php-webdav- 等)
            val dirtyExistingAlbums = allLocalAlbums.filter { MusicRepository.isDirtyAlbumName(it.name) }
            if (dirtyExistingAlbums.isNotEmpty()) {
                val cleanedExisting = dirtyExistingAlbums.map { album ->
                    val reformatted = MusicRepository.formatAlbumNameByLevels(album.remotePath, cleanFolder, effectiveLevels)
                    val finalName = if (!MusicRepository.isDirtyAlbumName(reformatted)) {
                        reformatted
                    } else {
                        MusicRepository.cleanDirtyAlbumName(album.name)
                    }
                    album.copy(name = finalName)
                }
                database.albumDao().insertAlbums(cleanedExisting)
                Log.d(TAG, "增量同步前已清洗 ${cleanedExisting.size} 筆現存髒資料專輯名稱")
            }

            val remoteAlbumPaths = mutableSetOf<String>()
            val unchangedAlbumIds = mutableSetOf<String>()

            val albumsToInsertOrUpdate = mutableListOf<AlbumEntity>()
            val tracksToInsertOrUpdate = mutableListOf<TrackEntity>()
            val modifiedAlbumIds = mutableListOf<String>()

            var addedCount = 0
            var modifiedCount = 0

            val queue = ArrayDeque<FolderScanNode>()
            queue.add(
                FolderScanNode(
                    folderUrl = targetBaseUrl,
                    relativeSegments = emptyList(),
                    inheritedCoverUrl = null
                )
            )

            var scannedFolderCount = 0

            while (!queue.isEmpty()) {
                val node = queue.poll() ?: break
                scannedFolderCount++

                val currentDisplayPath = if (node.relativeSegments.isEmpty()) "根目錄" else node.relativeSegments.joinToString("/")
                onProgress("正在同步：[$currentDisplayPath] ($scannedFolderCount)")

                val itemsResult = webDavClient.listFolder(node.folderUrl, depth = 1)
                if (itemsResult.isFailure) {
                    Log.w(TAG, "無法存取資料夾: ${node.folderUrl}")
                    continue
                }

                val items = itemsResult.getOrDefault(emptyList())
                val selfItem = items.find { isSamePath(it.href, node.folderUrl) }
                val currentFolderEtag = selfItem?.etag ?: node.knownEtag
                val currentFolderLastModified = selfItem?.lastModified ?: node.knownLastModified

                val directAudios = items.filter { it.isAudioFile }
                val directSubFolders = items.filter { it.isCollection && !isSamePath(it.href, node.folderUrl) }

                // 計算封面繼承
                val localCover = findCoverImage(items)
                val localCoverUrl = localCover?.let { webDavClient.resolveFullUrl(it.href) }
                val effectiveCoverUrl = if (node.relativeSegments.size <= 1) {
                    localCoverUrl
                } else {
                    localCoverUrl ?: node.inheritedCoverUrl
                }
                val coverToPassDown = if (node.relativeSegments.isEmpty()) {
                    null
                } else if (node.relativeSegments.size == 1) {
                    localCoverUrl
                } else {
                    effectiveCoverUrl
                }

                // 判定是否為含有音訊的專輯目錄
                if (directAudios.isNotEmpty()) {
                    val albumId = normalizePath(node.folderUrl)
                    remoteAlbumPaths.add(albumId)

                    val existing = localAlbumsMap[albumId]
                    val isEtagMatch = (existing?.etag != null && currentFolderEtag != null && existing.etag == currentFolderEtag) ||
                                      (existing?.etag == null && existing?.lastModified != null && currentFolderLastModified != null && existing.lastModified == currentFolderLastModified)

                    if (existing != null && isEtagMatch) {
                        unchangedAlbumIds.add(albumId)
                        Log.d(TAG, "略過未變更專輯曲目: $albumId (ETag=$currentFolderEtag)")
                    } else {
                        if (existing != null) {
                            modifiedCount++
                            modifiedAlbumIds.add(albumId)
                            Log.d(TAG, "偵測到專輯更新: $albumId (oldEtag=${existing.etag}, newEtag=$currentFolderEtag)")
                        } else {
                            addedCount++
                            Log.d(TAG, "偵測到新專輯: $albumId")
                        }

                        val rootName = if (cleanFolder.isEmpty()) "根目錄" else cleanFolder.substringAfterLast('/')
                        val levels = listOf(rootName) + node.relativeSegments
                        val currentFolderName = if (node.relativeSegments.isNotEmpty()) node.relativeSegments.last() else rootName
                        val rawDisplayName = MusicRepository.formatAlbumName(levels, effectiveLevels, currentFolderName)
                        val albumDisplayName = MusicRepository.cleanDirtyAlbumName(rawDisplayName)

                        val sortedAudios = directAudios.sortedWith { a, b ->
                            val nameA = a.displayName.ifBlank { a.href.substringAfterLast('/') }
                            val nameB = b.displayName.ifBlank { b.href.substringAfterLast('/') }
                            NaturalOrderComparator.compare(nameA, nameB)
                        }

                        // 本機自訂封面保護規則：若 isCustomLocalCover == true 絕不覆寫
                        val isCustomLocal = existing?.isCustomLocalCover == true && !existing.coverUrl.isNullOrBlank()
                        val finalCoverUrl = if (isCustomLocal) {
                            Log.d(TAG, "專輯 [$albumDisplayName] 保留本機自訂封面: ${existing.coverUrl}")
                            existing.coverUrl
                        } else {
                            effectiveCoverUrl
                        }

                        val album = AlbumEntity(
                            id = albumId,
                            name = albumDisplayName,
                            remotePath = node.folderUrl,
                            coverUrl = finalCoverUrl,
                            isCustomLocalCover = isCustomLocal,
                            trackCount = sortedAudios.size,
                            isDownloaded = existing?.isDownloaded ?: false,
                            etag = currentFolderEtag,
                            lastModified = currentFolderLastModified,
                            isFavorite = existing?.isFavorite ?: false
                        )
                        albumsToInsertOrUpdate.add(album)

                        sortedAudios.forEachIndexed { index, audioItem ->
                            tracksToInsertOrUpdate.add(createTrackEntity(audioItem, albumId, index + 1, finalCoverUrl))
                        }
                    }
                }

                // 走訪子目錄
                for (subFolder in directSubFolders) {
                    val subId = normalizePath(subFolder.href)
                    val subEtag = subFolder.etag
                    val subLastModified = subFolder.lastModified

                    val existingSub = localAlbumsMap[subId]
                    val isSubEtagMatch = (existingSub?.etag != null && subEtag != null && existingSub.etag == subEtag) ||
                                         (existingSub?.etag == null && existingSub?.lastModified != null && subLastModified != null && existingSub.lastModified == subLastModified)

                    // 檢查本機已知該資料夾是否具備更深層的子專輯
                    val hasKnownChildAlbums = localAlbumPaths.any { it != subId && it.startsWith("$subId/") }

                    // 若為已知專輯且 ETag 相同且無子專輯，完全略過不發送子請求
                    if (existingSub != null && isSubEtagMatch && !hasKnownChildAlbums) {
                        remoteAlbumPaths.add(subId)
                        unchangedAlbumIds.add(subId)
                        Log.d(TAG, "略過未變更子目錄: $subId (ETag=$subEtag)")
                        continue
                    }

                    val subFolderName = subFolder.displayName.ifBlank {
                        MusicRepository.extractCleanPath(subFolder.href).substringAfterLast('/')
                    }
                    val childSegments = node.relativeSegments + subFolderName
                    queue.add(
                        FolderScanNode(
                            folderUrl = subFolder.href,
                            relativeSegments = childSegments,
                            inheritedCoverUrl = coverToPassDown,
                            knownEtag = subEtag,
                            knownLastModified = subLastModified
                        )
                    )
                }
            }

            // 2. 第二步：處理已刪除的資料夾 (Deleted/Pruned)
            val deletedPaths = localAlbumPaths - remoteAlbumPaths
            if (deletedPaths.isNotEmpty()) {
                onProgress("正在清除 ${deletedPaths.size} 個已刪除資料夾...")
                Log.d(TAG, "正在清理遠端已刪除之專輯: $deletedPaths")

                // 從 Room 整批刪除
                val deletedAlbums = deletedPaths.mapNotNull { localAlbumsMap[it] }
                val deletedAlbumIds = (deletedPaths + deletedAlbums.map { it.id }).distinct()
                val deletedRemotePaths = deletedAlbums.map { it.remotePath }.distinct()

                database.trackDao().deleteTracksByAlbumIds(deletedAlbumIds)
                database.albumDao().deleteAlbumsByIds(deletedAlbumIds)
                if (deletedRemotePaths.isNotEmpty()) {
                    database.albumDao().deleteAlbumsByPaths(deletedRemotePaths)
                }

                // 清除本機儲存的快取與離線檔案
                for (deletedId in deletedPaths) {
                    val album = localAlbumsMap[deletedId] ?: continue
                    ArtworkHelper.invalidate(album.coverUrl)

                    context?.let { ctx ->
                        try {
                            val safeId = md5(album.id)
                            val customCoverFile = File(ctx.filesDir, "custom_covers/$safeId.jpg")
                            if (customCoverFile.exists()) {
                                customCoverFile.delete()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "刪除封面快取失敗: ${album.id}", e)
                        }

                        try {
                            val baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            if (baseDir != null) {
                                val safeAlbum = album.id.trimEnd('/').substringAfterLast('/')
                                val albumDir = File(baseDir, "albums/$safeAlbum")
                                if (albumDir.exists()) {
                                    albumDir.deleteRecursively()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "刪除離線檔案失敗: ${album.id}", e)
                        }
                    }
                }
            }

            // 3. 第三步：儲存新增與有異動的資料夾
            if (albumsToInsertOrUpdate.isNotEmpty()) {
                onProgress("發現 $addedCount 個新資料夾、$modifiedCount 個變更，正在存入快取...")
                Log.d(TAG, "Writing ${albumsToInsertOrUpdate.size} albums to database")

                if (modifiedAlbumIds.isNotEmpty()) {
                    database.trackDao().deleteTracksByAlbumIds(modifiedAlbumIds)
                }

                val checkedTracks = checkExistingLocalFiles(tracksToInsertOrUpdate)
                database.albumDao().insertAlbums(albumsToInsertOrUpdate)
                database.trackDao().insertTracks(checkedTracks)
            }

            val finalAlbums = database.albumDao().getAllAlbumsList()
            val finalTotalTracks = finalAlbums.sumOf { it.trackCount }

            onProgress("快速同步完成！新增 $addedCount、更新 $modifiedCount、刪除 ${deletedPaths.size}，現有 $finalTotalTracks 首歌曲")
            Log.d(TAG, "Sync finished: added=$addedCount, modified=$modifiedCount, deleted=${deletedPaths.size}, unchanged=${unchangedAlbumIds.size}")

            Result.success(
                SyncResult(
                    addedCount = addedCount,
                    modifiedCount = modifiedCount,
                    deletedCount = deletedPaths.size,
                    unchangedCount = unchangedAlbumIds.size,
                    totalTracks = finalTotalTracks,
                    totalAlbums = finalAlbums.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "增量同步過程失敗", e)
            Result.failure(e)
        }
    }

    /**
     * 強制完整重新掃描 (Full Rescan)：
     * 清空本地音樂庫快取後重新進行完整抓取與中繼資料解析
     */
    suspend fun fullRescan(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        try {
            onProgress("正在清空本地音樂庫快取...")
            Log.d(TAG, "Performing full rescan: clearing local database")
            val preservedFavorites = try {
                database.albumDao().getAllAlbumsList().filter { it.isFavorite }.map { it.id }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            database.albumDao().clearAlbums()
            database.trackDao().clearTracks()

            val result = incrementalSync(
                scopedFolder = scopedFolder,
                selectedLevels = selectedLevels,
                onProgress = onProgress
            )
            if (preservedFavorites.isNotEmpty()) {
                preservedFavorites.forEach { favId ->
                    database.albumDao().updateFavoriteStatus(favId, true)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "完整重新掃描異常", e)
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

    companion object {
        private const val TAG = "WebDavSyncRepository"

        fun normalizePath(path: String): String {
            val decoded = try {
                URLDecoder.decode(path, "UTF-8")
            } catch (e: Exception) {
                path
            }
            val noHost = if (decoded.contains("://")) {
                decoded.substringAfter("://").substringAfter('/', "")
            } else {
                decoded
            }
            val clean = noHost.trim().trim('/')
            return if (clean.isEmpty()) "/" else "/$clean"
        }
    }

    private fun isSamePath(path1: String, path2: String): Boolean {
        val ep1 = normalizePath(path1)
        val ep2 = normalizePath(path2)
        return ep1.equals(ep2, ignoreCase = true)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
