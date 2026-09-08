package com.nextcloud.musicplayer.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nextcloud.musicplayer.NextcloudMusicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模組 3：將音樂庫掃描遷移至 WorkManager 前景任務（背景常駐不中斷）
 *
 * 1. 執行掃描時，立即掛載 foregroundServiceType="dataSync" 前景通知。
 * 2. 即時回報進度（例如：正在同步：[資料夾名稱] (15/48)）。
 * 3. 支援退至背景持續執行，重新開啟 App 時 UI 透過 StateFlow 監聽 WorkManager 狀態並自動刷新。
 */
class SyncLibraryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "SyncLibraryWorker"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val scopedFolder = inputData.getString(KEY_SCOPED_FOLDER) ?: ""
        val isFullRescan = inputData.getBoolean(KEY_IS_FULL_RESCAN, false)

        createNotificationChannel()

        val initialMessage = if (isFullRescan) {
            "正在初始化強制完整重新掃描..."
        } else {
            "正在初始化快速增量同步..."
        }

        try {
            setForeground(createForegroundInfo(initialMessage))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set initial foreground: ${e.message}")
        }

        setProgress(
            workDataOf(
                KEY_PROGRESS_MESSAGE to initialMessage,
                KEY_IS_RUNNING to true
            )
        )

        val app = applicationContext as? NextcloudMusicApp
        if (app == null) {
            Log.e(TAG, "Application is not NextcloudMusicApp")
            notificationManager.cancel(NOTIFICATION_ID)
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "應用程式初始化異常"))
        }

        val repository = app.musicRepository

        try {
            val syncResult = if (isFullRescan) {
                repository.fullRescan(
                    scopedFolder = scopedFolder,
                    onProgress = { msg ->
                        updateProgress(msg)
                    }
                )
            } else {
                repository.incrementalSync(
                    scopedFolder = scopedFolder,
                    onProgress = { msg ->
                        updateProgress(msg)
                    }
                )
            }

            notificationManager.cancel(NOTIFICATION_ID)

            if (syncResult.isSuccess) {
                val res = syncResult.getOrNull()
                val summaryMessage = if (isFullRescan) {
                    "完整重新掃描完成！共找到 ${res?.totalAlbums ?: 0} 張專輯、${res?.totalTracks ?: 0} 首歌曲"
                } else {
                    "快速同步完成！新增 ${res?.addedCount ?: 0}、更新 ${res?.modifiedCount ?: 0}、刪除 ${res?.deletedCount ?: 0} 張專輯，現有 ${res?.totalTracks ?: 0} 首歌曲"
                }
                Log.i(TAG, "Sync finished successfully: $summaryMessage")

                Result.success(
                    workDataOf(
                        KEY_PROGRESS_MESSAGE to summaryMessage,
                        KEY_IS_RUNNING to false,
                        KEY_ADDED to (res?.addedCount ?: 0),
                        KEY_MODIFIED to (res?.modifiedCount ?: 0),
                        KEY_DELETED to (res?.deletedCount ?: 0),
                        KEY_TOTAL_TRACKS to (res?.totalTracks ?: 0),
                        KEY_TOTAL_ALBUMS to (res?.totalAlbums ?: 0)
                    )
                )
            } else {
                val err = syncResult.exceptionOrNull()?.localizedMessage ?: "同步發生未知錯誤"
                Log.e(TAG, "Sync failed: $err")
                Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to err,
                        KEY_PROGRESS_MESSAGE to "同步失敗: $err",
                        KEY_IS_RUNNING to false
                    )
                )
            }
        } catch (e: Exception) {
            notificationManager.cancel(NOTIFICATION_ID)
            Log.e(TAG, "SyncLibraryWorker crashed", e)
            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to (e.localizedMessage ?: "掃描異常"),
                    KEY_PROGRESS_MESSAGE to "掃描異常: ${e.localizedMessage}",
                    KEY_IS_RUNNING to false
                )
            )
        }
    }

    private fun updateProgress(message: String) {
        if (isStopped) return
        try {
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_MESSAGE to message,
                    KEY_IS_RUNNING to true
                )
            )
            setForegroundAsync(createForegroundInfo(message))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification / progress: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音樂庫同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "背景同步 Nextcloud 音樂庫與中繼資料"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Nextcloud 音樂庫同步")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        const val UNIQUE_WORK_NAME = "sync_library_work"
        const val CHANNEL_ID = "nextcloud_music_sync_channel"
        const val NOTIFICATION_ID = 9528

        const val KEY_SCOPED_FOLDER = "scoped_folder"
        const val KEY_IS_FULL_RESCAN = "is_full_rescan"
        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_IS_RUNNING = "is_running"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ADDED = "added_count"
        const val KEY_MODIFIED = "modified_count"
        const val KEY_DELETED = "deleted_count"
        const val KEY_TOTAL_TRACKS = "total_tracks"
        const val KEY_TOTAL_ALBUMS = "total_albums"

        fun startSync(
            context: Context,
            scopedFolder: String = "",
            isFullRescan: Boolean = false
        ) {
            val workRequest = OneTimeWorkRequestBuilder<SyncLibraryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SCOPED_FOLDER to scopedFolder,
                        KEY_IS_FULL_RESCAN to isFullRescan
                    )
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
