package com.nextcloud.musicplayer.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.auth.LoginFlowV2Client
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    data class Loading(val message: String) : LoginUiState()
    object Success : LoginUiState()
    data class Error(val error: String) : LoginUiState()
}

data class PublicShareInfo(
    val baseUrl: String,
    val shareToken: String
)

class LoginViewModel(
    private val prefsManager: SecurePreferencesManager,
    private val loginFlowClient: LoginFlowV2Client,
    private val webDavClient: NextcloudWebDavClient? = null
) : ViewModel() {

    private val TAG = "LoginViewModel"
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private var pollingJob: Job? = null

    val isAlreadyLoggedIn: Boolean
        get() = prefsManager.hasCredentials()

    /**
     * 模組 1：僅保留 Nextcloud 官方 Login Flow v2 授權協議流程
     */
    fun startLoginFlowV2(serverUrl: String, context: Context) {
        val cleanServer = serverUrl.trim().removeSuffix("/")
        if (cleanServer.isBlank() || cleanServer == "https://" || cleanServer == "http://") {
            val err = "請輸入有效的 Nextcloud 伺服器網址"
            _uiState.value = LoginUiState.Error(err)
            viewModelScope.launch { _toastEvent.emit(err) }
            return
        }

        _uiState.value = LoginUiState.Loading("正在發起 Nextcloud 官方授權請求...")

        viewModelScope.launch {
            val initResult = loginFlowClient.initiateLogin(cleanServer)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.localizedMessage ?: "無法連接 Nextcloud 伺服器"
                Log.e(TAG, "Login flow init failed: $err", initResult.exceptionOrNull())
                _uiState.value = LoginUiState.Error("連線失敗：$err")
                _toastEvent.emit(err)
                return@launch
            }

            val flowData = initResult.getOrThrow()

            // 開啟瀏覽器或 Custom Tabs 讓使用者於 Nextcloud 網頁授權
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(flowData.loginUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (e: Exception) {
                val err = "無法開啟瀏覽器授權頁面: ${e.message}"
                Log.e(TAG, err, e)
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
                return@launch
            }

            _uiState.value = LoginUiState.Loading("請於瀏覽器完成登入授權，等待伺服器回傳憑證...")

            // 輪詢授權狀態取得 appPassword
            pollingJob?.cancel()
            pollingJob = viewModelScope.launch {
                val pollResult = loginFlowClient.pollForCredentials(
                    endpoint = flowData.poll.endpoint,
                    token = flowData.poll.token
                )

                if (pollResult.isSuccess) {
                    val creds = pollResult.getOrThrow()
                    prefsManager.saveCredentials(creds.server, creds.loginName, creds.appPassword)
                    _uiState.value = LoginUiState.Success
                    _toastEvent.emit("Nextcloud 授權登入成功！")
                } else {
                    val err = pollResult.exceptionOrNull()?.localizedMessage ?: "授權逾時或已取消"
                    Log.e(TAG, "Polling failed: $err")
                    _uiState.value = LoginUiState.Error(err)
                    _toastEvent.emit(err)
                }
            }
        }
    }

    /**
     * 模組 5：透過 Nextcloud 公開分享連結 (Public Share Link) 連線
     *
     * 1. 支援格式：https://<domain>/s/<token> 或 https://<domain>/index.php/s/<token>
     * 2. 端點：https://<domain>/public.php/webdav/
     * 3. 認證：Basic Auth (使用者名稱為 token，密碼為分享密碼；無密碼時留空)
     */
    fun connectWithPublicShare(shareUrl: String, password: String = "") {
        val cleanUrl = shareUrl.trim()
        val info = parsePublicShareUrl(cleanUrl)
        if (info == null) {
            val err = "公開分享連結格式不符，範例：https://cloud.example.com/s/AbCdEf123456"
            _uiState.value = LoginUiState.Error(err)
            viewModelScope.launch { _toastEvent.emit(err) }
            return
        }

        _uiState.value = LoginUiState.Loading("正在驗證公開分享連結與 WebDAV 連線...")

        viewModelScope.launch {
            val client = webDavClient
            if (client == null) {
                // 若無 client 實例直接儲存憑證
                prefsManager.savePublicShareCredentials(info.baseUrl, info.shareToken, password)
                _uiState.value = LoginUiState.Success
                _toastEvent.emit("已設定公開分享連結！")
                return@launch
            }

            val testResult = client.testPublicShareConnection(
                serverUrl = info.baseUrl,
                shareToken = info.shareToken,
                password = password
            )

            if (testResult.isSuccess) {
                prefsManager.savePublicShareCredentials(info.baseUrl, info.shareToken, password)
                _uiState.value = LoginUiState.Success
                _toastEvent.emit("公開分享連結連線成功！")
            } else {
                val err = testResult.exceptionOrNull()?.localizedMessage ?: "公開 WebDAV 連線失敗"
                Log.e(TAG, "Public share connection failed: $err")
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
            }
        }
    }

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }

    companion object {
        /**
         * 解析 Nextcloud 公開分享網址
         * 範例：
         * - https://cloud.example.com/s/AbCdEf123456
         * - https://cloud.example.com/index.php/s/AbCdEf123456
         * - https://cloud.example.com/nextcloud/s/AbCdEf123456/
         */
        fun parsePublicShareUrl(url: String): PublicShareInfo? {
            val clean = url.trim().removeSuffix("/")
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                return null
            }
            // 標準化去除路徑中之 index.php/s/
            val normalized = if (clean.contains("/index.php/s/")) {
                clean.replace("/index.php/s/", "/s/")
            } else {
                clean
            }
            val regex = Regex("""^(https?://[^/]+(?:/.*?)?)/s/([a-zA-Z0-9_\-]+)$""")
            val match = regex.find(normalized) ?: return null
            val baseUrl = match.groupValues[1].removeSuffix("/index.php").removeSuffix("/")
            val token = match.groupValues[2]
            return PublicShareInfo(baseUrl, token)
        }
    }
}
