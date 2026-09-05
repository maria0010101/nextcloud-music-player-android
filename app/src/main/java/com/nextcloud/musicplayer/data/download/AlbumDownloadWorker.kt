package com.nextcloud.musicplayer.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.R
import com.nextcloud.musicplayer.data.local.AppDatabase
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AlbumDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "AlbumDownloadWorker"
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

        val tracks = database.trackDao().getTracksForAlbumSync(albumId)
        if (tracks.isEmpty()) {
            Log.w(TAG, "No tracks found in database for album: $albumId")
            return Result.failure()
        }

        val baseDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: applicationContext.filesDir
        val safeAlbumDirName = albumName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val albumDir = File(baseDir, "albums/$safeAlbumDirName")
        if (!albumDir.exists()) {
            albumDir.mkdirs()
        }

        val totalTracks = tracks.size
        Log.i(TAG, "開始下載專輯 [$albumName], 共有 $totalTracks 首曲目，儲存目錄: ${albumDir.absolutePath}")

        for ((index, track) in tracks.withIndex()) {
            if (isStopped) {
                Log.w(TAG, "Download worker stopped by system")
                return Result.failure()
            }

            val currentNum = index + 1
            val percent = (currentNum * 100) / totalTracks
            val progressText = "$currentNum/$totalTracks 下載中 ($percent%) - ${track.title}"

            setProgress(
                workDataOf(
                    KEY_PROGRESS to percent,
                    KEY_CURRENT to currentNum,
                    KEY_TOTAL to totalTracks,
                    KEY_CURRENT_TITLE to track.title
                )
            )

            try {
                setForeground(createForegroundInfo(progressText, percent))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to setForeground: ${e.message}")
            }

            val safeFilename = "${track.trackNumber.toString().padStart(2, '0')} - ${track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.${track.format.lowercase()}"
            val targetFile = File(albumDir, safeFilename)

            // 若本機檔案已存在且大小完整，直接標記跳過
            if (targetFile.exists() && targetFile.length() > 0 && targetFile.length() == track.fileSize) {
                database.trackDao().markTrackDownloaded(track.id, targetFile.absolutePath)
                continue
            }

            // 下載曲目
            try {
                val request = Request.Builder()
                    .url(track.streamUrl)
                    .header("User-Agent", "NextcloudMusicPlayer/1.0 (Android)")
                    .header("OCS-APIREQUEST", "true")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download track: HTTP ${response.code} for ${track.title}")
                    continue
                }

                val body = response.body ?: continue
                FileOutputStream(targetFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                database.trackDao().markTrackDownloaded(track.id, targetFile.absolutePath)
                Log.d(TAG, "已下載曲目 ($currentNum/$totalTracks): ${targetFile.name} (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "下載曲目異常: ${track.title}", e)
            }
        }

        database.albumDao().markAlbumDownloaded(albumId, true)
        Log.i(TAG, "專輯 [$albumName] 全部曲目下載完成！")

        notificationManager.cancel(NOTIFICATION_ID)
        return Result.success(workDataOf(KEY_ALBUM_ID to albumId))
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
            val workRequest = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
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
