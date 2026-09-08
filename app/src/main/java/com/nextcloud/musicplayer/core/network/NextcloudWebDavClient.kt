package com.nextcloud.musicplayer.core.network

import android.util.Base64
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NextcloudWebDavClient(
    private val okHttpClient: OkHttpClient,
    private val prefsManager: SecurePreferencesManager
) {

    private val propfindRequestBody = """
        <?xml version="1.0" encoding="utf-8" ?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:displayname/>
            <d:resourcetype/>
            <d:getcontentlength/>
            <d:getcontenttype/>
            <d:getlastmodified/>
            <d:getetag/>
          </d:prop>
        </d:propfind>
    """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

    suspend fun testConnection(
        serverUrl: String,
        username: String,
        appPassword: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanServer = serverUrl.trim().removeSuffix("/")
            val testUrl = "$cleanServer/remote.php/dav/files/$username/"
            val credentials = "$username:$appPassword"
            val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

            val request = Request.Builder()
                .url(testUrl)
                .method("PROPFIND", propfindRequestBody)
                .header("Depth", "0")
                .header("Authorization", authHeader)
                .header("OCS-APIREQUEST", "true")
                .header("User-Agent", "NextcloudMusicPlayer/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 207) {
                Result.success(true)
            } else {
                Result.failure(IOException("Server returned HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testPublicShareConnection(
        serverUrl: String,
        shareToken: String,
        password: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanServer = serverUrl.trim().removeSuffix("/")
            val testUrl = "$cleanServer/public.php/webdav/"
            val credentials = "$shareToken:$password"
            val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

            val request = Request.Builder()
                .url(testUrl)
                .method("PROPFIND", propfindRequestBody)
                .header("Depth", "0")
                .header("Authorization", authHeader)
                .header("OCS-APIREQUEST", "true")
                .header("User-Agent", "NextcloudMusicPlayer/1.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 207) {
                Result.success(true)
            } else if (response.code == 401) {
                Result.failure(IOException("HTTP 401: 密碼錯誤或需要密碼保護"))
            } else {
                Result.failure(IOException("Server returned HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFolder(remotePath: String, depth: Int = 1): Result<List<WebDavItem>> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = resolveFullUrl(remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", propfindRequestBody)
                .header("Depth", depth.toString())
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 207) {
                return@withContext Result.failure(
                    IOException("PROPFIND failed HTTP ${response.code}: ${response.message}")
                )
            }

            val bodyStream = response.body?.byteStream()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val items = WebDavXmlParser.parse(bodyStream)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun resolveFullUrl(pathOrHref: String): String {
        val serverUrl = prefsManager.getServerUrl()?.removeSuffix("/") ?: ""
        return if (pathOrHref.startsWith("http://") || pathOrHref.startsWith("https://")) {
            pathOrHref
        } else if (pathOrHref.startsWith("/")) {
            "$serverUrl$pathOrHref"
        } else {
            val base = prefsManager.getWebDavBaseUrl()?.removeSuffix("/") ?: serverUrl
            "$base/$pathOrHref"
        }
    }

    suspend fun uploadAlbumCover(
        remoteFolderPath: String,
        imageBytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val server = prefsManager.getServerUrl()?.removeSuffix("/")
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 伺服器網址"))
            val username = prefsManager.getLoginName()
                ?: return@withContext Result.failure(IllegalStateException("未設定 Nextcloud 帳號/分享識別碼"))
            val webDavBase = prefsManager.getWebDavBaseUrl()?.removeSuffix("/") ?: "$server/remote.php/dav/files/$username"

            val targetUrl = if (remoteFolderPath.startsWith("http://") || remoteFolderPath.startsWith("https://")) {
                "${remoteFolderPath.trimEnd('/')}/cover.jpg"
            } else {
                val cleanRel = remoteFolderPath.trim().trim('/')
                if (cleanRel.startsWith("remote.php/dav/files/$username/", ignoreCase = true) ||
                    cleanRel.startsWith("public.php/webdav/", ignoreCase = true)) {
                    "$server/${cleanRel.trimEnd('/')}/cover.jpg"
                } else {
                    "$webDavBase/$cleanRel/cover.jpg"
                }
            }

            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .put(requestBody)
                .header("Content-Type", "image/jpeg")

            prefsManager.getBasicAuthHeader()?.let { auth ->
                requestBuilder.header("Authorization", auth)
            }

            val request = requestBuilder.build()
            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful || response.code in 200..204) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("WebDAV PUT failed HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

typealias WebDavClient = NextcloudWebDavClient

