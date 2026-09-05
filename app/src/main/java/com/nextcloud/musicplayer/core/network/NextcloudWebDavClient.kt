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
}
