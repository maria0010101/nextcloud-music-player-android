package com.nextcloud.musicplayer.core.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.URLDecoder

data class QrParsedResult(
    val serverUrl: String,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null
)

object NextcloudQrParser {

    private const val TAG = "NextcloudQrParser"
    private val gson = Gson()

    private data class QrJsonPayload(
        @SerializedName("server") val server: String?,
        @SerializedName("serverUrl") val serverUrl: String?,
        @SerializedName("user") val user: String?,
        @SerializedName("loginName") val loginName: String?,
        @SerializedName("password") val password: String?,
        @SerializedName("appPassword") val appPassword: String?,
        @SerializedName("token") val token: String?
    )

    fun parse(rawQrContent: String): Result<QrParsedResult> {
        val trimmed = rawQrContent.trim()
        Log.d(TAG, "Parsing QR Code content: $trimmed")

        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("QR Code 內容為空"))
        }

        // 1. Nextcloud Custom Scheme: nc://login/... or nextcloud://login/...
        if (trimmed.startsWith("nc://", ignoreCase = true) ||
            trimmed.startsWith("nextcloud://", ignoreCase = true)
        ) {
            return parseNcScheme(trimmed)
        }

        // 2. JSON Format: {"server":"...", "user":"...", "password":"..."}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJsonPayload(trimmed)
        }

        // 3. Direct HTTP(S) URL
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return parseHttpUrl(trimmed)
        }

        return Result.failure(
            IllegalArgumentException("無法識別的 QR Code 格式 (內容: ${trimmed.take(50)}...)")
        )
    }

    private fun parseNcScheme(uriString: String): Result<QrParsedResult> {
        return try {
            // Examples:
            // nc://login/server:https%3A%2F%2Fcloud.example.com&user:john&password:secret
            // nc://login/server:https://cloud.example.com&token:abcdef12345
            // nc://login?server=...
            val payload = uriString
                .substringAfter("://login/")
                .substringAfter("://login?")
                .substringAfter("://")

            val params = mutableMapOf<String, String>()

            // Split by '&'
            val pairs = payload.split('&')
            for (pair in pairs) {
                val colonIdx = pair.indexOf(':')
                val equalIdx = pair.indexOf('=')
                val delimiterIdx = when {
                    colonIdx != -1 && equalIdx != -1 -> minOf(colonIdx, equalIdx)
                    colonIdx != -1 -> colonIdx
                    else -> equalIdx
                }

                if (delimiterIdx != -1) {
                    val key = pair.substring(0, delimiterIdx).trim().lowercase()
                    val rawVal = pair.substring(delimiterIdx + 1).trim()
                    val decodedVal = try {
                        URLDecoder.decode(rawVal, "UTF-8")
                    } catch (e: Exception) {
                        rawVal
                    }
                    params[key] = decodedVal
                }
            }

            val server = params["server"] ?: params["serverurl"]
            if (server.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("QR Code 內缺少伺服器網址 (server)"))
            }

            val user = params["user"] ?: params["loginname"] ?: params["username"]
            val password = params["password"] ?: params["apppassword"]
            val token = params["token"]

            Result.success(
                QrParsedResult(
                    serverUrl = cleanUrl(server),
                    username = user,
                    password = password,
                    token = token
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse nc:// scheme", e)
            Result.failure(IllegalArgumentException("解析 nc:// 協議失敗: ${e.localizedMessage}"))
        }
    }

    private fun parseJsonPayload(jsonString: String): Result<QrParsedResult> {
        return try {
            val payload = gson.fromJson(jsonString, QrJsonPayload::class.java)
            val server = payload.server ?: payload.serverUrl
            if (server.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("JSON 內缺少 server 欄位"))
            }

            Result.success(
                QrParsedResult(
                    serverUrl = cleanUrl(server),
                    username = payload.user ?: payload.loginName,
                    password = payload.password ?: payload.appPassword,
                    token = payload.token
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON payload", e)
            Result.failure(IllegalArgumentException("解析 JSON 憑證失敗: ${e.localizedMessage}"))
        }
    }

    private fun parseHttpUrl(urlString: String): Result<QrParsedResult> {
        val clean = cleanUrl(urlString)
        // Check if it's a login flow URL: e.g. https://domain/index.php/login/v2/flow/<token>
        if (clean.contains("/login/v2/flow/")) {
            val token = clean.substringAfter("/login/v2/flow/").substringBefore('/')
            val server = clean.substringBefore("/index.php")
            return Result.success(
                QrParsedResult(
                    serverUrl = server,
                    token = token
                )
            )
        }

        // Just server URL
        return Result.success(QrParsedResult(serverUrl = clean))
    }

    private fun cleanUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }
}
