package com.nextcloud.musicplayer.data.repository

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

    fun getAlbums(): Flow<List<AlbumEntity>> = database.albumDao().getAllAlbums()

    fun getTracksForAlbum(albumId: String): Flow<List<TrackEntity>> =
        database.trackDao().getTracksForAlbum(albumId)

    suspend fun getAlbumById(albumId: String): AlbumEntity? =
        database.albumDao().getAlbumById(albumId)

    suspend fun scanMusicLibrary(
        baseFolder: String = "",
        onProgress: (message: String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val rootHref = prefsManager.getWebDavBaseUrl()
                ?: return@withContext Result.failure(IllegalStateException("No server credentials configured"))

            val targetPath = if (baseFolder.isBlank()) rootHref else "$rootHref/$baseFolder"
            onProgress("正在掃描 Nextcloud 根目錄...")

            val rootItemsResult = webDavClient.listFolder(targetPath, depth = 1)
            if (rootItemsResult.isFailure) {
                return@withContext Result.failure(
                    rootItemsResult.exceptionOrNull() ?: Exception("Failed to list files")
                )
            }

            val rootItems = rootItemsResult.getOrDefault(emptyList())

            // Filter out the root itself (WebDAV depth: 1 includes the target directory)
            val subFolders = rootItems.filter { it.isCollection && !isSamePath(it.href, targetPath) }
            val rootAudios = rootItems.filter { it.isAudioFile }

            val discoveredAlbums = mutableListOf<AlbumEntity>()
            val discoveredTracks = mutableListOf<TrackEntity>()

            // If root has audio files, treat root as an album
            if (rootAudios.isNotEmpty()) {
                val rootCover = findCoverImage(rootItems)
                val rootAlbumId = normalizePath(targetPath)
                val rootAlbum = AlbumEntity(
                    id = rootAlbumId,
                    name = "根目錄音樂",
                    remotePath = targetPath,
                    coverUrl = rootCover?.let { webDavClient.resolveFullUrl(it.href) },
                    trackCount = rootAudios.size
                )
                discoveredAlbums.add(rootAlbum)

                rootAudios.forEachIndexed { index, audioItem ->
                    discoveredTracks.add(createTrackEntity(audioItem, rootAlbumId, index + 1))
                }
            }

            // Scan each subfolder (treated as an Album)
            for ((index, folder) in subFolders.withIndex()) {
                val folderName = folder.displayName.ifBlank {
                    folder.href.trimEnd('/').substringAfterLast('/')
                }
                onProgress("掃描資料夾 (${index + 1}/${subFolders.size}): $folderName")

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

                // If subfolder contains nested artist/album folders, scan them 1 level deeper
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

            onProgress("儲存中，共發現 ${discoveredAlbums.size} 張專輯、${discoveredTracks.size} 首曲目...")

            // Persist to Room Database
            database.albumDao().clearAlbums()
            database.trackDao().clearTracks()
            database.albumDao().insertAlbums(discoveredAlbums)
            database.trackDao().insertTracks(discoveredTracks)

            onProgress("掃描同步完成！")
            Result.success(discoveredTracks.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findCoverImage(items: List<WebDavItem>): WebDavItem? {
        val images = items.filter { it.isImageFile }
        if (images.isEmpty()) return null

        // 1. Check for standard cover filenames
        val preferredNames = listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "album.jpg", "front.jpg")
        for (preferred in preferredNames) {
            val match = images.find { it.displayName.equals(preferred, ignoreCase = true) }
            if (match != null) return match
        }

        // 2. Fallback to any image file
        return images.firstOrNull()
    }

    private fun createTrackEntity(audioItem: WebDavItem, albumId: String, trackNumber: Int): TrackEntity {
        val rawTitle = audioItem.displayName.ifBlank {
            audioItem.href.trimEnd('/').substringAfterLast('/')
        }
        val cleanTitle = rawTitle.substringBeforeLast('.')
            .replaceFirst(Regex("^[0-9]+[.\\s\\-_]+"), "") // Remove leading "01 - " or "01. "

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
