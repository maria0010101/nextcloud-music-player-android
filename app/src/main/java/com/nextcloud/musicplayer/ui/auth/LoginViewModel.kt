package com.nextcloud.musicplayer.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class LoginViewModel(
    private val prefsManager: SecurePreferencesManager,
    private val loginFlowClient: LoginFlowV2Client
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

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }
}
