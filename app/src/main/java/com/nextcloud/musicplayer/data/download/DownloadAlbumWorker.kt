package com.nextcloud.musicplayer.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.core.settings.DataStoreManager
import com.nextcloud.musicplayer.data.local.AppDatabase
import com.nextcloud.musicplayer.data.local.entity.TrackEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DownloadAlbumWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "DownloadAlbumWorker"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val albumId = inputData.getString(KEY_ALBUM_ID) ?: return Result.failure()
        val albumName = inputData.getString(KEY_ALBUM_NAME) ?: "音樂專輯"

        createNotificationChannel()
        setForeground(createForegroundInfo("準備下載專輯: $albumName", 0))

        val app = applicationContext as NextcloudMusicApp
        val database = AppDatabase.getInstance(applicationContext)
        val okHttpClient = app.authenticatedOkHttpClient

        val album = database.albumDao().getAlbumById(albumId)
        val tracks = database.trackDao().getTracksForAlbumSync(albumId)
        if (tracks.isEmpty()) {
            Log.w(TAG, "No tracks found in database for album: $albumId")
            return Result.failure()
        }

        // 模組 3：路徑取得邏輯 - 先自 DataStore 讀取使用者設定的下載目標
        val dataStoreManager = DataStoreManager(applicationContext)
        val customStorageUriString = dataStoreManager.getDownloadStorageUriSync()

        val safeAlbumDirName = albumName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val totalTracks = tracks.size

        Log.i(TAG, "開始下載專輯 [$albumName], 共 $totalTracks 首曲目, 自訂 SAF: ${customStorageUriString != null}")

        val isCustomSaf = !customStorageUriString.isNullOrBlank()

        if (isCustomSaf) {
            // 分支 A：自訂 SAF Uri 寫入模式
            val success = downloadToSafTree(
                customStorageUriString = customStorageUriString!!,
                safeAlbumDirName = safeAlbumDirName,
                albumName = albumName,
                tracks = tracks,
                coverUrl = album?.coverUrl,
                okHttpClient = okHttpClient,
                database = database,
                albumId = albumId
            )
            if (!success) return Result.failure()
        } else {
            // 分支 B：預設路徑 (App 專屬外部儲存空間)
            val success = downloadToDefaultDirectory(
                safeAlbumDirName = safeAlbumDirName,
                albumName = albumName,
                tracks = tracks,
                coverUrl = album?.coverUrl,
                okHttpClient = okHttpClient,
                database = database,
                albumId = albumId
            )
            if (!success) return Result.failure()
        }

        database.albumDao().markAlbumDownloaded(albumId, true)
        Log.i(TAG, "專輯 [$albumName] 全部曲目下載完成！")

        notificationManager.cancel(NOTIFICATION_ID)
        return Result.success(workDataOf(KEY_ALBUM_ID to albumId))
    }

    /**
     * 自訂 SAF DocumentTree 目錄寫入適配
     */
    private suspend fun downloadToSafTree(
        customStorageUriString: String,
        safeAlbumDirName: String,
        albumName: String,
        tracks: List<TrackEntity>,
        coverUrl: String?,
        okHttpClient: OkHttpClient,
        database: AppDatabase,
        albumId: String
    ): Boolean {
        val treeUri = Uri.parse(customStorageUriString)
        val rootDoc = DocumentFile.fromTreeUri(applicationContext, treeUri)
        if (rootDoc == null || !rootDoc.canWrite()) {
            Log.e(TAG, "無法寫入所選之自訂 SAF 目錄: $customStorageUriString")
            return false
        }

        // 在自訂目錄下建立專輯名稱子資料夾
        val albumDoc = rootDoc.findFile(safeAlbumDirName)
            ?: rootDoc.createDirectory(safeAlbumDirName)

        if (albumDoc == null) {
            Log.e(TAG, "無法在 SAF 目錄下建立專輯資料夾: $safeAlbumDirName")
            return false
        }

        val totalTracks = tracks.size

        for ((index, track) in tracks.withIndex()) {
            if (isStopped) {
                Log.w(TAG, "Download worker stopped by system")
                return false
            }

            val currentNum = index + 1
            val percent = (currentNum * 100) / totalTracks
            val progressText = "$currentNum/$totalTracks 下載中 ($percent%) - ${track.title}"

            updateProgress(percent, currentNum, totalTracks, track.title, progressText)

            val safeFilename = "${track.trackNumber.toString().padStart(2, '0')} - ${track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.${track.format.lowercase()}"
            val mimeType = getAudioMimeType(track)

            val existingDoc = albumDoc.findFile(safeFilename)
            if (existingDoc != null && existingDoc.length() > 0 && existingDoc.length() == track.fileSize) {
                database.trackDao().markTrackDownloaded(track.id, existingDoc.uri.toString())
                continue
            }

            val targetDoc = existingDoc ?: albumDoc.createFile(mimeType, safeFilename)
            if (targetDoc == null) {
                Log.e(TAG, "無法建立 SAF 檔案: $safeFilename")
                continue
            }

            try {
                val outputStream = applicationContext.contentResolver.openOutputStream(targetDoc.uri, "wt")
                if (outputStream != null) {
                    val downloaded = downloadStreamToFile(track.streamUrl, outputStream, okHttpClient)
                    if (downloaded) {
                        // 模組 4：將 SAF Content Uri 回寫至 Room 資料庫
                        database.trackDao().markTrackDownloaded(track.id, targetDoc.uri.toString())
                        Log.d(TAG, "已下載 SAF 曲目 ($currentNum/$totalTracks): $safeFilename -> ${targetDoc.uri}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SAF 下載曲目異常: ${track.title}", e)
            }
        }

        // 下載專輯封面 cover.jpg
        if (!coverUrl.isNullOrBlank()) {
            try {
                val existingCover = albumDoc.findFile("cover.jpg")
                val coverDoc = existingCover ?: albumDoc.createFile("image/jpeg", "cover.jpg")
                if (coverDoc != null && (existingCover == null || coverDoc.length() == 0L)) {
                    applicationContext.contentResolver.openOutputStream(coverDoc.uri, "wt")?.use { output ->
                        downloadStreamToFile(coverUrl, output, okHttpClient)
                    }
                    Log.d(TAG, "已同步下載專輯封面至 SAF: cover.jpg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "下載 SAF 專輯封面略過或失敗", e)
            }
        }

        return true
    }

    /**
     * 預設路徑 (App 專屬外部儲存空間: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)/Downloads/)
     */
    private suspend fun downloadToDefaultDirectory(
        safeAlbumDirName: String,
        albumName: String,
        tracks: List<TrackEntity>,
        coverUrl: String?,
        okHttpClient: OkHttpClient,
        database: AppDatabase,
        albumId: String
    ): Boolean {
        val baseDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: applicationContext.filesDir
        val downloadsDir = File(baseDir, "Downloads")
        val albumDir = File(downloadsDir, safeAlbumDirName)
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }

        val totalTracks = tracks.size

        for ((index, track) in tracks.withIndex()) {
            if (isStopped) {
                Log.w(TAG, "Download worker stopped by system")
                return false
            }

            val currentNum = index + 1
            val percent = (currentNum * 100) / totalTracks
            val progressText = "$currentNum/$totalTracks 下載中 ($percent%) - ${track.title}"

            updateProgress(percent, currentNum, totalTracks, track.title, progressText)

            val safeFilename = "${track.trackNumber.toString().padStart(2, '0')} - ${track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.${track.format.lowercase()}"
            val targetFile = File(albumDir, safeFilename)

            // 若本機檔案已存在且大小完整，直接標記跳過
            if (targetFile.exists() && targetFile.length() > 0 && targetFile.length() == track.fileSize) {
                database.trackDao().markTrackDownloaded(track.id, targetFile.absolutePath)
                continue
            }

            try {
                FileOutputStream(targetFile).use { output ->
                    val downloaded = downloadStreamToFile(track.streamUrl, output, okHttpClient)
                    if (downloaded) {
                        // 模組 4：將本地絕對路徑回寫至 Room 資料庫
                        database.trackDao().markTrackDownloaded(track.id, targetFile.absolutePath)
                        Log.d(TAG, "已下載曲目 ($currentNum/$totalTracks): ${targetFile.name} (${targetFile.length()} bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "下載曲目異常: ${track.title}", e)
            }
        }

        // 下載專輯封面 cover.jpg
        if (!coverUrl.isNullOrBlank()) {
            try {
                val coverFile = File(albumDir, "cover.jpg")
                if (!coverFile.exists() || coverFile.length() == 0L) {
                    FileOutputStream(coverFile).use { output ->
                        downloadStreamToFile(coverUrl, output, okHttpClient)
                    }
                    Log.d(TAG, "已同步下載專輯封面: cover.jpg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "下載專輯封面略過或失敗", e)
            }
        }

        return true
    }

    private fun downloadStreamToFile(
        url: String,
        output: OutputStream,
        okHttpClient: OkHttpClient
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NextcloudMusicPlayer/1.0 (Android)")
                .header("OCS-APIREQUEST", "true")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed HTTP ${response.code} for URL: $url")
                return false
            }

            val body = response.body ?: return false
            output.use { out ->
                body.byteStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stream download error: $url", e)
            false
        }
    }

    private suspend fun updateProgress(
        percent: Int,
        currentNum: Int,
        totalTracks: Int,
        title: String,
        progressText: String
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_CURRENT to currentNum,
                KEY_TOTAL to totalTracks,
                KEY_CURRENT_TITLE to title
            )
        )
        try {
            setForeground(createForegroundInfo(progressText, percent))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to setForeground: ${e.message}")
        }
    }

    private fun getAudioMimeType(track: TrackEntity): String {
        return track.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: when (track.format.lowercase()) {
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "m4a", "aac" -> "audio/mp4"
                "opus" -> "audio/opus"
                else -> "audio/mpeg"
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "離線音樂下載",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "背景下載 Nextcloud 專輯檔案"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(message: String, progressPercent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Nextcloud 音樂下載")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "nextcloud_music_download_channel"
        const val NOTIFICATION_ID = 9527

        const val KEY_ALBUM_ID = "album_id"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_TITLE = "current_title"

        fun startDownload(context: Context, albumId: String, albumName: String) {
            val workRequest = OneTimeWorkRequestBuilder<DownloadAlbumWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ALBUM_ID to albumId,
                        KEY_ALBUM_NAME to albumName
                    )
                )
                .addTag("download_album_$albumId")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
