package com.nextcloud.musicplayer.core.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.auth.LoginFlowV2Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

sealed class QrLoginResult {
    data class Success(val serverUrl: String, val loginName: String) : QrLoginResult()
    data class InProgress(val message: String) : QrLoginResult()
    data class Error(val userFriendlyMessage: String, val technicalDetail: String? = null) : QrLoginResult()
}

data class NextcloudQrPayload(
    val serverUrl: String,
    val loginName: String? = null,
    val appPassword: String? = null,
    val token: String? = null
)

class NextcloudQrLoginManager(
    private val webDavClient: NextcloudWebDavClient,
    private val loginFlowClient: LoginFlowV2Client,
    private val prefsManager: SecurePreferencesManager
) {

    private val TAG = "NextcloudQrLogin"
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

    fun parseQrCode(rawContent: String): Result<NextcloudQrPayload> {
        val trimmed = rawContent.trim()
        Log.d(TAG, "=== 開始解析 QR Code 內容 ===")
        Log.d(TAG, "原始長度: ${trimmed.length}, 前 80 字元: ${trimmed.take(80)}")

        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("QR Code 內容為空"))
        }

        // 1. Nextcloud 專用協議 nc://login/... 或 nextcloud://login/...
        if (trimmed.startsWith("nc://", ignoreCase = true) ||
            trimmed.startsWith("nextcloud://", ignoreCase = true)
        ) {
            return parseNcProtocol(trimmed)
        }

        // 2. JSON 格式
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseJsonFormat(trimmed)
        }

        // 3. 標準 HTTP(S) URL
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return parseHttpUrl(trimmed)
        }

        Log.e(TAG, "無法識別的 QR Code 格式: $trimmed")
        return Result.failure(
            IllegalArgumentException("QR Code 格式不符合 Nextcloud 標準\n(內容前綴: ${trimmed.take(30)})")
        )
    }

    private fun parseNcProtocol(uri: String): Result<NextcloudQrPayload> {
        return try {
            val queryPart = uri.substringAfter("://login/")
                .substringAfter("://login?")
                .substringAfter("://")

            val params = mutableMapOf<String, String>()
            val pairs = queryPart.split('&')
            for (pair in pairs) {
                val colonIdx = pair.indexOf(':')
                val equalIdx = pair.indexOf('=')
                val splitIdx = when {
                    colonIdx != -1 && equalIdx != -1 -> minOf(colonIdx, equalIdx)
                    colonIdx != -1 -> colonIdx
                    else -> equalIdx
                }

                if (splitIdx != -1) {
                    val k = pair.substring(0, splitIdx).trim().lowercase()
                    val v = pair.substring(splitIdx + 1).trim()
                    val decoded = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
                    params[k] = decoded
                }
            }

            val server = params["server"] ?: params["serverurl"]
            if (server.isNullOrBlank()) {
                Log.e(TAG, "nc:// 協議中缺少 server 欄位, 參數列表: ${params.keys}")
                return Result.failure(IllegalArgumentException("QR Code 內缺少伺服器網址 (server)"))
            }

            val user = params["user"] ?: params["loginname"] ?: params["username"]
            val pass = params["password"] ?: params["apppassword"]
            val token = params["token"]

            Log.i(TAG, "解析 nc:// 成功: server=$server, user=$user, hasPass=${pass != null}, hasToken=${token != null}")
            Result.success(
                NextcloudQrPayload(
                    serverUrl = cleanUrl(server),
                    loginName = user,
                    appPassword = pass,
                    token = token
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseNcProtocol 發生異常", e)
            Result.failure(IllegalArgumentException("解析 nc:// 協議失敗: ${e.message}"))
        }
    }

    private fun parseJsonFormat(json: String): Result<NextcloudQrPayload> {
        return try {
            val item = gson.fromJson(json, QrJsonPayload::class.java)
            val server = item.server ?: item.serverUrl
            if (server.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("JSON 缺少 server 伺服器網址"))
            }

            val user = item.user ?: item.loginName
            val pass = item.password ?: item.appPassword
            val token = item.token

            Log.i(TAG, "解析 JSON 成功: server=$server, user=$user, hasPass=${pass != null}, hasToken=${token != null}")
            Result.success(
                NextcloudQrPayload(
                    serverUrl = cleanUrl(server),
                    loginName = user,
                    appPassword = pass,
                    token = token
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseJsonFormat 異常", e)
            Result.failure(IllegalArgumentException("JSON 格式無效: ${e.message}"))
        }
    }

    private fun parseHttpUrl(url: String): Result<NextcloudQrPayload> {
        val clean = cleanUrl(url)
        if (clean.contains("/login/v2/flow/")) {
            val token = clean.substringAfter("/login/v2/flow/").substringBefore('/')
            val server = clean.substringBefore("/index.php")
            Log.i(TAG, "解析 LoginFlow URL 成功: server=$server, token=$token")
            return Result.success(NextcloudQrPayload(serverUrl = server, token = token))
        }
        Log.i(TAG, "解析標準 URL 成功: server=$clean")
        return Result.success(NextcloudQrPayload(serverUrl = clean))
    }

    suspend fun authenticateFromQr(
        rawContent: String,
        onProgress: (String) -> Unit = {}
    ): QrLoginResult = withContext(Dispatchers.IO) {
        val parseResult = parseQrCode(rawContent)
        if (parseResult.isFailure) {
            val err = parseResult.exceptionOrNull()?.localizedMessage ?: "QR Code 格式不符合 Nextcloud 標準"
            return@withContext QrLoginResult.Error(err)
        }

        val payload = parseResult.getOrThrow()
        Log.d(TAG, "開始處理憑證換取與驗證: server=${payload.serverUrl}, user=${payload.loginName}")

        // 情況 A：QR Code 帶有完整的帳號與 App Password
        if (!payload.loginName.isNullOrBlank() && !payload.appPassword.isNullOrBlank()) {
            onProgress("正在向 Nextcloud 驗證帳號密碼...")
            val testResult = webDavClient.testConnection(payload.serverUrl, payload.loginName, payload.appPassword)
            if (testResult.isSuccess) {
                prefsManager.saveCredentials(payload.serverUrl, payload.loginName, payload.appPassword)
                Log.i(TAG, "QR 憑證驗證成功，已安全保存")
                return@withContext QrLoginResult.Success(payload.serverUrl, payload.loginName)
            } else {
                val ex = testResult.exceptionOrNull()
                val msg = ex?.localizedMessage ?: ""
                Log.e(TAG, "QR 密碼驗證失敗: $msg", ex)
                val friendly = if (msg.contains("401")) {
                    "解析成功但伺服器拒絕 (HTTP 401：帳號或密碼錯誤)"
                } else if (msg.contains("404")) {
                    "伺服器端點不存在 (HTTP 404)"
                } else {
                    "連線至伺服器失敗：${ex?.message ?: "連線超時"}"
                }
                return@withContext QrLoginResult.Error(friendly, msg)
            }
        }

        // 情況 B：QR Code 帶有授權 Token (Login Flow v2)
        if (!payload.token.isNullOrBlank()) {
            onProgress("已讀取授權 Token，正在向伺服器確認授權狀態...")
            val pollEndpoint = "${payload.serverUrl}/index.php/login/v2/poll"
            Log.d(TAG, "發送 Token 輪詢請求至: $pollEndpoint, token: ${payload.token}")

            val pollResult = loginFlowClient.pollForCredentials(
                endpoint = pollEndpoint,
                token = payload.token,
                maxRetries = 15,
                intervalMs = 2000
            )

            if (pollResult.isSuccess) {
                val creds = pollResult.getOrThrow()
                prefsManager.saveCredentials(creds.server, creds.loginName, creds.appPassword)
                Log.i(TAG, "Token 換取憑證成功: server=${creds.server}, user=${creds.loginName}")
                return@withContext QrLoginResult.Success(creds.server, creds.loginName)
            } else {
                val ex = pollResult.exceptionOrNull()
                Log.e(TAG, "Token 換取失敗", ex)
                return@withContext QrLoginResult.Error(
                    "Token 授權確認失敗：請確認網頁端已點擊授權或 Token 尚未過期",
                    ex?.localizedMessage
                )
            }
        }

        // 情況 C：QR 僅包含伺服器網址
        return@withContext QrLoginResult.Error("QR Code 僅包含伺服器網址，請使用「官方授權」按鈕開啟瀏覽器登入")
    }

    private fun cleanUrl(url: String): String = url.trim().removeSuffix("/")
}
