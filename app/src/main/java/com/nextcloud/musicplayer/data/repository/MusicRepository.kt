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

    fun getFavoriteAlbums(): Flow<List<AlbumEntity>> = database.albumDao().getFavoriteAlbumsFlow()

    suspend fun getTracksFromFavoriteAlbums(): List<TrackEntity> = withContext(Dispatchers.IO) {
        database.albumDao().getTracksFromFavoriteAlbums()
    }

    suspend fun updateAlbumFavorite(albumId: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        database.albumDao().updateFavoriteStatus(albumId, isFavorite)
    }

    fun getTracksForAlbum(albumId: String): Flow<List<TrackEntity>> =
        database.trackDao().getTracksForAlbum(albumId)

    suspend fun getAlbumById(albumId: String): AlbumEntity? =
        database.albumDao().getAlbumById(albumId)

    fun observeAlbumById(albumId: String): Flow<AlbumEntity?> =
        database.albumDao().observeAlbumById(albumId)

    suspend fun updateAlbumCover(albumId: String, coverUrl: String?, isCustomLocalCover: Boolean) = withContext(Dispatchers.IO) {
        database.albumDao().updateAlbumCover(albumId, coverUrl, isCustomLocalCover)
        database.trackDao().updateCoverForAlbumTracks(albumId, coverUrl)
    }

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

        fun isSamePath(path1: String, path2: String): Boolean {
            val ep1 = extractCleanPath(path1)
            val ep2 = extractCleanPath(path2)
            return ep1.equals(ep2, ignoreCase = true)
        }
    }

    val syncRepository = WebDavSyncRepository(webDavClient, database, prefsManager, context, settingsDataStore)

    suspend fun incrementalSync(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<SyncResult> = syncRepository.incrementalSync(scopedFolder, selectedLevels, onProgress)

    suspend fun fullRescan(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<SyncResult> = syncRepository.fullRescan(scopedFolder, selectedLevels, onProgress)

    /**
     * 掃描音樂庫 (預設採用增量快速差分同步)
     */
    suspend fun scanMusicLibrary(
        scopedFolder: String = "",
        selectedLevels: Set<Int>? = null,
        onProgress: (message: String) -> Unit = {}
    ): Result<Int> = syncRepository.incrementalSync(scopedFolder, selectedLevels, onProgress).map { it.totalTracks }
}
