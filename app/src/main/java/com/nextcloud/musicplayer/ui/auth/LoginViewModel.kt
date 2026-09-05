package com.nextcloud.musicplayer.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.network.NextcloudQrLoginManager
import com.nextcloud.musicplayer.core.network.NextcloudWebDavClient
import com.nextcloud.musicplayer.core.network.QrLoginResult
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
    private val webDavClient: NextcloudWebDavClient,
    private val loginFlowClient: LoginFlowV2Client,
    private val qrLoginManager: NextcloudQrLoginManager? = null
) : ViewModel() {

    private val TAG = "LoginViewModel"
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private var pollingJob: Job? = null

    val isAlreadyLoggedIn: Boolean
        get() = prefsManager.hasCredentials()

    fun testAndSaveManualCredentials(serverUrl: String, username: String, appPassword: String) {
        if (serverUrl.isBlank() || username.isBlank() || appPassword.isBlank()) {
            val err = "請完整填寫伺服器網址、帳號與密碼"
            _uiState.value = LoginUiState.Error(err)
            viewModelScope.launch { _toastEvent.emit(err) }
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
                Log.e(TAG, "手動登入測試失敗: $errorMsg", result.exceptionOrNull())
                _uiState.value = LoginUiState.Error(errorMsg)
                _toastEvent.emit("驗證失敗: $errorMsg")
            }
        }
    }

    fun startLoginFlowV2(serverUrl: String, context: Context) {
        if (serverUrl.isBlank()) {
            val err = "請輸入 Nextcloud 伺服器網址"
            _uiState.value = LoginUiState.Error(err)
            viewModelScope.launch { _toastEvent.emit(err) }
            return
        }

        _uiState.value = LoginUiState.Loading("正在發起 Nextcloud Login Flow v2 授權...")

        viewModelScope.launch {
            val initResult = loginFlowClient.initiateLogin(serverUrl)
            if (initResult.isFailure) {
                val err = initResult.exceptionOrNull()?.localizedMessage ?: "無法連接 Nextcloud 伺服器"
                Log.e(TAG, "Login flow init failed: $err", initResult.exceptionOrNull())
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
                return@launch
            }

            val flowData = initResult.getOrThrow()

            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(flowData.loginUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (e: Exception) {
                val err = "無法開啟瀏覽器進行授權: ${e.message}"
                Log.e(TAG, err, e)
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
                return@launch
            }

            _uiState.value = LoginUiState.Loading("請於瀏覽器完成登入授權，等待伺服器回傳憑證...")

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
                    _toastEvent.emit("Nextcloud 授權成功！")
                } else {
                    val err = pollResult.exceptionOrNull()?.localizedMessage ?: "授權超時或失敗"
                    Log.e(TAG, "Polling failed: $err")
                    _uiState.value = LoginUiState.Error(err)
                    _toastEvent.emit(err)
                }
            }
        }
    }

    /**
     * 模組 1：相機掃描 QR Code 登入完整流程與錯誤攔截
     */
    fun handleScannedQrCode(qrContent: String, context: Context) {
        Log.i(TAG, "開始處理相機掃描到的 QR Code...")
        _uiState.value = LoginUiState.Loading("正在解析 QR Code 憑證...")

        viewModelScope.launch {
            val manager = qrLoginManager ?: NextcloudQrLoginManager(webDavClient, loginFlowClient, prefsManager)
            val result = manager.authenticateFromQr(
                rawContent = qrContent,
                onProgress = { stepMsg -> _uiState.value = LoginUiState.Loading(stepMsg) }
            )

            when (result) {
                is QrLoginResult.Success -> {
                    Log.i(TAG, "QR 登入成功: server=${result.serverUrl}, user=${result.loginName}")
                    _uiState.value = LoginUiState.Success
                    _toastEvent.emit("Nextcloud 授權登入成功！")
                }
                is QrLoginResult.Error -> {
                    Log.e(TAG, "QR 登入失敗: ${result.userFriendlyMessage}")
                    _uiState.value = LoginUiState.Error(result.userFriendlyMessage)
                    _toastEvent.emit(result.userFriendlyMessage)
                }
                is QrLoginResult.InProgress -> {
                    _uiState.value = LoginUiState.Loading(result.message)
                }
            }
        }
    }

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }
}
