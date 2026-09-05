package com.nextcloud.musicplayer.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.data.auth.LoginFlowV2Client
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    data class Loading(val message: String) : LoginUiState()
    object Success : LoginUiState()
    data class Error(val error: String) : LoginUiState()
}

class LoginViewModel(
    private val prefsManager: SecurePreferencesManager,
    private val webDavClient: NextcloudWebDavClient,
    private val loginFlowClient: LoginFlowV2Client
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    val isAlreadyLoggedIn: Boolean
        get() = prefsManager.hasCredentials()

    fun testAndSaveManualCredentials(serverUrl: String, username: String, appPassword: String) {
        if (serverUrl.isBlank() || username.isBlank() || appPassword.isBlank()) {
            _uiState.value = LoginUiState.Error("請完整填寫伺服器網址、帳號與密碼")
            return
        }

        _uiState.value = LoginUiState.Loading("正在驗證 WebDAV 連線...")

        viewModelScope.launch {
            val result = webDavClient.testConnection(serverUrl, username, appPassword)
            if (result.isSuccess) {
                prefsManager.saveCredentials(serverUrl, username, appPassword)
                _uiState.value = LoginUiState.Success
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "連線失敗，請檢查網址或帳號密碼"
                _uiState.value = LoginUiState.Error(errorMsg)
            }
        }
    }

    fun startLoginFlowV2(serverUrl: String, context: Context) {
        if (serverUrl.isBlank()) {
            _uiState.value = LoginUiState.Error("請輸入 Nextcloud 伺服器網址")
            return
        }

        _uiState.value = LoginUiState.Loading("正在發起 Nextcloud Login Flow v2 授權...")

        viewModelScope.launch {
            val initResult = loginFlowClient.initiateLogin(serverUrl)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.localizedMessage ?: "無法連接 Nextcloud 伺服器"
                _uiState.value = LoginUiState.Error(err)
                return@launch
            }

            val flowData = initResult.getOrThrow()

            // Open browser for authorization
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(flowData.loginUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error("無法開啟瀏覽器進行授權: ${e.message}")
                return@launch
            }

            _uiState.value = LoginUiState.Loading("請於瀏覽器完成登入授權，等待伺服器回傳憑證...")

            // Start polling for credentials
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
                } else {
                    _uiState.value = LoginUiState.Error(
                        pollResult.exceptionOrNull()?.localizedMessage ?: "授權超時或失敗"
                    )
                }
            }
        }
    }

    fun handleScannedQrCode(qrContent: String, context: Context) {
        // Nextcloud login QR code usually contains URL or nc:// token
        if (qrContent.startsWith("http://") || qrContent.startsWith("https://")) {
            startLoginFlowV2(qrContent, context)
        } else {
            _uiState.value = LoginUiState.Error("未識別的 QR Code 格式")
        }
    }

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }
}
