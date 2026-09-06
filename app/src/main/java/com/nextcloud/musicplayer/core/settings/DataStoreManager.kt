package com.nextcloud.musicplayer.core.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.net.URLDecoder

class DataStoreManager(private val context: Context) {

    private val TAG = "DataStoreManager"

    companion object {
        val KEY_DOWNLOAD_STORAGE_URI = stringPreferencesKey("download_storage_uri_key")
    }

    /**
     * 讀取所選的離線下載 SAF 目錄 URI 字串 (為 null 或空白代表預設內部路徑)
     */
    val downloadStorageUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_STORAGE_URI]?.takeIf { it.isNotBlank() }
    }

    /**
     * 同步取得當前設定的自訂 SAF URI 字串 (在背景協程中讀取)
     */
    suspend fun getDownloadStorageUriSync(): String? {
        return context.dataStore.data.firstOrNull()?.get(KEY_DOWNLOAD_STORAGE_URI)?.takeIf { it.isNotBlank() }
    }

    /**
     * 儲存離線下載目錄 URI 字串至 DataStore
     */
    suspend fun saveDownloadStorageUri(uriString: String?) {
        context.dataStore.edit { prefs ->
            if (uriString.isNullOrBlank()) {
                prefs.remove(KEY_DOWNLOAD_STORAGE_URI)
            } else {
                prefs[KEY_DOWNLOAD_STORAGE_URI] = uriString.trim()
            }
        }
    }

    /**
     * 模組 1 關鍵授權持久化：呼叫 takePersistableUriPermission 保存 URI 讀寫權限，
     * 確保 App 重啟或手機重新開機後仍具備該目錄的讀寫權限。
     */
    fun takePersistableUriPermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "成功取得持久化 SAF 權限: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "取得持久化 SAF 權限失敗: $uri", e)
        }
    }

    /**
     * 釋放持久化授權 (當使用者恢復預設或切換目錄時)
     */
    fun releasePersistableUriPermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
            Log.d(TAG, "已釋放 SAF 持久化權限: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "釋放 SAF 持久化權限失敗: $uri", e)
        }
    }

    /**
     * 友善解析顯示資料夾名稱 (例如：內部儲存空間 > Music)，避免顯示冗長晦澀的 URI 編碼字串
     */
    fun formatStorageLocation(uriString: String?): String {
        if (uriString.isNullOrBlank()) {
            return "App 預設空間 (Music/Downloads)"
        }

        return try {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val storageName = when {
                parts[0].equals("primary", ignoreCase = true) -> "內部儲存空間"
                parts[0].isNotBlank() -> "SD 卡 (${parts[0]})"
                else -> "自訂目錄"
            }
            val subPath = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].trim('/').replace("/", " > ")
            } else {
                ""
            }
            if (subPath.isNotBlank()) "$storageName > $subPath" else storageName
        } catch (e: Exception) {
            try {
                val docFile = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                docFile?.name?.let { "自訂目錄 > $it" } ?: URLDecoder.decode(uriString, "UTF-8")
            } catch (_: Exception) {
                uriString
            }
        }
    }
}
