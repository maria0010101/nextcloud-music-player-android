package com.nextcloud.musicplayer.data.auth

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class LoginFlowInitResponse(
    @SerializedName("poll") val poll: PollInfo,
    @SerializedName("login") val loginUrl: String
)

data class PollInfo(
    @SerializedName("token") val token: String,
    @SerializedName("endpoint") val endpoint: String
)

data class LoginFlowSuccessResponse(
    @SerializedName("server") val server: String,
    @SerializedName("loginName") val loginName: String,
    @SerializedName("appPassword") val appPassword: String
)

class LoginFlowV2Client(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson = Gson()
) {

    suspend fun initiateLogin(serverUrl: String): Result<LoginFlowInitResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanServer = serverUrl.trim().removeSuffix("/")
            val initUrl = "$cleanServer/index.php/login/v2"

            val request = Request.Builder()
                .url(initUrl)
                .post(FormBody.Builder().build())
                .header("User-Agent", "NextcloudMusicPlayer/1.0")
                .header("OCS-APIREQUEST", "true")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Failed to initiate login flow: HTTP ${response.code} ${response.message}")
                )
            }

            val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty body"))
            val initResponse = gson.fromJson(body, LoginFlowInitResponse::class.java)
            Result.success(initResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollForCredentials(
        endpoint: String,
        token: String,
        maxRetries: Int = 60,
        intervalMs: Long = 3000
    ): Result<LoginFlowSuccessResponse> = withContext(Dispatchers.IO) {
        var retries = 0
        while (retries < maxRetries) {
            try {
                val formBody = FormBody.Builder()
                    .add("token", token)
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(formBody)
                    .header("User-Agent", "NextcloudMusicPlayer/1.0")
                    .header("OCS-APIREQUEST", "true")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val successData = gson.fromJson(body, LoginFlowSuccessResponse::class.java)
                            return@withContext Result.success(successData)
                        }
                    }
                    404 -> {
                        // Still waiting for user authorization in browser
                    }
                    else -> {
                        // Other status codes might indicate expiry or rejection
                    }
                }
            } catch (e: Exception) {
                // Ignore transient network errors during polling
            }

            retries++
            delay(intervalMs)
        }

        Result.failure(IOException("Login authorization timed out. Please try again."))
    }
}
