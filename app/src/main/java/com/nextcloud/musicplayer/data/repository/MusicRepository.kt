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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
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
    private val context: Context? = null,
    private val settingsDataStore: AppSettingsDataStore? = null
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

    /**
     * 當使用者在設定頁切換專輯名稱階層勾選項時，免重新掃描網路，直接重新格式化本機資料庫內的所有專輯名稱
     */
    suspend fun reapplyAlbumNameLevels(
        selectedLevels: Set<Int>,
        scanRootDir: String
    ) = withContext(Dispatchers.IO) {
        val albums = database.albumDao().getAllAlbumsList()
        if (albums.isEmpty()) return@withContext

        val updatedAlbums = albums.map { album ->
            val newName = formatAlbumNameByLevels(album.remotePath, scanRootDir, selectedLevels)
            album.copy(name = newName)
        }
        database.albumDao().insertAlbums(updatedAlbums)
        Log.d(TAG, "已依自訂階層重新格式化 ${updatedAlbums.size} 張專輯名稱")
    }

    private data class ScanFolderNode(
        val folderUrl: String,
        val relativeSegments: List<String>,
        val inheritedCoverUrl: String?
    )

    companion object {
        /**
         * 判斷是否為常見音訊檔案
         */
        fun isAudioFilePath(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in listOf("mp3", "flac", "aac", "wav", "ogg", "m4a", "opus", "wma", "ape", "alac", "aiff")
        }

        /**
         * 提取 URL 或路徑的純路徑部分 (解碼後並移除 protocol 與 host)
         */
        fun extractCleanPath(urlOrPath: String): String {
            val decoded = try {
                URLDecoder.decode(urlOrPath, "UTF-8")
            } catch (e: Exception) {
                urlOrPath
            }
            return decoded.substringAfter("://").substringAfter('/', decoded).trimEnd('/')
        }

        /**
         * 計算目標 URL/路徑相對於掃描根目錄的路徑片段 (Segments)
         */
        fun extractRelativeSegments(currentUrlOrPath: String, rootUrlOrPath: String): List<String> {
            val currentPath = extractCleanPath(currentUrlOrPath)
            val rootPath = extractCleanPath(rootUrlOrPath)

            if (currentPath.equals(rootPath, ignoreCase = true)) {
                return emptyList()
            }

            if (currentPath.startsWith("$rootPath/", ignoreCase = true)) {
                val rel = currentPath.substring(rootPath.length + 1).trim('/')
                return if (rel.isBlank()) emptyList() else rel.split('/').filter { it.isNotBlank() }
            }

            return emptyList()
        }

        /**
         * 根據階層陣列 (levels) 與使用者勾選之階層 (selectedLevels) 格式化專輯名稱：
         * levels: [階層1 (掃描目錄), 階層2, 階層3, 階層4, 階層5, ...]
         *
         * 規則：
         * 1. 僅篩選出「使用者有勾選」且「該路徑實際存在」的階層名稱 (依序以 " - " 串接)。
         * 2. 若該資料夾深度未達某勾選階層，自動略過。
         * 3. 若全部取消勾選或篩選後結果為空，強制回退顯示音訊檔案所在的當前資料夾名稱。
         */
        fun formatAlbumName(
            levels: List<String>,
            selectedLevels: Set<Int>,
            currentFolderName: String = levels.lastOrNull().orEmpty().ifBlank { "未分類單曲" }
        ): String {
            val filteredNames = (1..5).mapNotNull { levelIdx ->
                if (levelIdx in selectedLevels && levelIdx <= levels.size) {
                    levels[levelIdx - 1].takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }

            return if (filteredNames.isEmpty()) {
                currentFolderName.ifBlank { "未分類單曲" }
            } else {
                filteredNames.joinToString(" - ")
            }
        }

        /**
         * 接收 selectedLevels 與完整路徑並輸出專輯名稱的格式化函式
         *
         * @param fullPath 完整路徑 (檔案或目錄，如 "/Music/Rock/Classic/Pink Floyd/The Wall/01.mp3")
         * @param scanRootDir 掃描目錄 (如 "/Music")
         * @param selectedLevels 使用者選取的階層集合 (如 setOf(2, 3))
         */
        fun formatAlbumNameByLevels(
            fullPath: String,
            scanRootDir: String,
            selectedLevels: Set<Int>
        ): String {
            val cleanScanRoot = extractCleanPath(scanRootDir)
            val rootName = if (cleanScanRoot.isBlank()) "根目錄" else cleanScanRoot.substringAfterLast('/')

            val cleanFullPath = extractCleanPath(fullPath)
            val folderPath = if (isAudioFilePath(cleanFullPath)) {
                cleanFullPath.substringBeforeLast('/')
            } else {
                cleanFullPath
            }

            val relativeSegments = if (folderPath.equals(cleanScanRoot, ignoreCase = true)) {
                emptyList()
            } else if (folderPath.startsWith("$cleanScanRoot/", ignoreCase = true)) {
                folderPath.substring(cleanScanRoot.length + 1).split('/').filter { it.isNotBlank() }
            } else {
                folderPath.trim('/').split('/').filter { it.isNotBlank() }
            }

            val levels = listOf(rootName) + relativeSegments
            val currentFolderName = if (relativeSegments.isNotEmpty()) {
                relativeSegments.last()
            } else {
                rootName
            }

            return formatAlbumName(levels, selectedLevels, currentFolderName)
        }

        /**
         * 保留相容舊版直接傳入 relativeSegments 的函式 (預設階層 2, 3)
         */
        fun formatAlbumName(relativeSegments: List<String>): String {
            return when {
                relativeSegments.isEmpty() -> "未分類單曲"
                relativeSegments.size == 1 -> relativeSegments[0]
                relativeSegments.size == 2 -> "${relativeSegments[0]} - ${relativeSegments[1]}"
                else -> "${relativeSegments[0]} - ${relativeSegments.drop(1).joinToString(" - ")}"
            }
        }
    }

    /**
     * 模組 2：資料夾層級名稱與封面繼承演算法
     * 1. 專輯名稱格式化：基於掃描根目錄相對路徑段格式化，掃描根目錄絕對不可出現於專輯名稱中。
     * 2. 封面繼承回退邊界：本目錄封面優先 > 自動向上查找母資料夾封面。終點為母資料夾，絕不向上查找到掃描根目錄。
     * 3. 曲目自然排序：依照檔案名稱進行自然排序。
     * 4. 母目錄過濾：本身無音訊的母目錄禁止作為獨立專輯。
     */
    suspend fun scanMusicLibrary(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val effectiveLevels = selectedLevels
                ?: settingsDataStore?.albumNameLevels?.firstOrNull()
                ?: AppSettingsDataStore.DEFAULT_ALBUM_NAME_LEVELS

            val rootHref = prefsManager.getWebDavBaseUrl()
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 帳號憑證"))

            val cleanFolder = scopedFolder.trim().trim('/')
            val targetBaseUrl = if (cleanFolder.isEmpty()) rootHref.trimEnd('/') else "${rootHref.trimEnd('/')}/$cleanFolder"

            onProgress("正在連線 WebDAV: /$cleanFolder...")
            Log.d(TAG, "Starting scoped scan in: $targetBaseUrl")

            val queue = ArrayDeque<ScanFolderNode>()
            queue.add(
                ScanFolderNode(
                    folderUrl = targetBaseUrl,
                    relativeSegments = emptyList(),
                    inheritedCoverUrl = null
                )
            )

            val discoveredAlbums = mutableListOf<AlbumEntity>()
            val discoveredTracks = mutableListOf<TrackEntity>()
            var folderCount = 0

            while (!queue.isEmpty()) {
                val node = queue.poll() ?: break
                folderCount++

                val currentDisplayPath = if (node.relativeSegments.isEmpty()) "根目錄" else node.relativeSegments.joinToString("/")
                onProgress("掃描資料夾 ($folderCount): $currentDisplayPath")

                val itemsResult = webDavClient.listFolder(node.folderUrl, depth = 1)
                if (itemsResult.isFailure) {
                    Log.w(TAG, "無法存取資料夾: ${node.folderUrl}")
                    continue
                }

                val items = itemsResult.getOrDefault(emptyList())
                val directAudios = items.filter { it.isAudioFile }
                val directSubFolders = items.filter { it.isCollection && !isSamePath(it.href, node.folderUrl) }

                // 封面優先順序判定與 Fallback 邊界限制：
                // 1. 本目錄封面 (localCover) 優先。
                // 2. 邊界限制：向上查找終點為「母資料夾」(relativeSegments.size == 1)，不得向上查找到「掃描根目錄」。
                //    - 根目錄 (depth 0): 自身曲目可使用 localCover，但向子目錄傳遞 null。
                //    - 母資料夾 (depth 1): 只使用自身 localCover (無 inheritedCoverUrl)，若有 localCover 則向子目錄傳遞。
                //    - 子資料夾 (depth >= 2): 自身 localCover 優先，若無則 fallback 至 node.inheritedCoverUrl (來自母資料夾)。
                val localCover = findCoverImage(items)
                val localCoverUrl = localCover?.let { webDavClient.resolveFullUrl(it.href) }

                val effectiveCoverUrl = if (node.relativeSegments.size <= 1) {
                    localCoverUrl
                } else {
                    localCoverUrl ?: node.inheritedCoverUrl
                }

                // 計算要向下傳遞給子目錄的封面 (掃描根目錄絕不向下傳遞)
                val coverToPassDown = if (node.relativeSegments.isEmpty()) {
                    null // 掃描根目錄下的通用圖片絕不套用為子專輯封面
                } else if (node.relativeSegments.size == 1) {
                    localCoverUrl // 母資料夾自身封面
                } else {
                    effectiveCoverUrl // 子資料夾已繼承或自身的封面
                }

                // 判定是否為含有音訊的專輯目錄
                if (directAudios.isNotEmpty()) {
                    // 根據使用者自訂之階層設定產生符合規格的專輯名稱
                    val rootName = if (cleanFolder.isEmpty()) "根目錄" else cleanFolder.substringAfterLast('/')
                    val levels = listOf(rootName) + node.relativeSegments
                    val currentFolderName = if (node.relativeSegments.isNotEmpty()) node.relativeSegments.last() else rootName
                    val albumDisplayName = formatAlbumName(levels, effectiveLevels, currentFolderName)

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
                    Log.d(TAG, "資料夾 [$currentDisplayPath] 內無直接音訊，不建立獨立專輯")
                }

                // 將子資料夾加入走訪佇列
                for (subFolder in directSubFolders) {
                    val subFolderName = subFolder.displayName.ifBlank {
                        extractCleanPath(subFolder.href).substringAfterLast('/')
                    }
                    val childSegments = node.relativeSegments + subFolderName
                    queue.add(
                        ScanFolderNode(
                            folderUrl = subFolder.href,
                            relativeSegments = childSegments,
                            inheritedCoverUrl = coverToPassDown
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
        val ep1 = extractCleanPath(path1)
        val ep2 = extractCleanPath(path2)
        return ep1.equals(ep2, ignoreCase = true)
    }
}
