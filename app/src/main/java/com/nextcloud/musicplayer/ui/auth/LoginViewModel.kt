package com.nextcloud.musicplayer.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.musicplayer.core.network.NextcloudQrParser
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

class LoginViewModel(
    private val prefsManager: SecurePreferencesManager,
    private val webDavClient: NextcloudWebDavClient,
    private val loginFlowClient: LoginFlowV2Client
) : ViewModel() {

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
                Log.e("LoginViewModel", "Manual login test failed: $errorMsg", result.exceptionOrNull())
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
                Log.e("LoginViewModel", "Login flow init failed: $err", initResult.exceptionOrNull())
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
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
                val err = "無法開啟瀏覽器進行授權: ${e.message}"
                Log.e("LoginViewModel", err, e)
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
                return@launch
            }

            _uiState.value = LoginUiState.Loading("請於瀏覽器完成登入授權，等待伺服器回傳憑證...")

            // Start polling for credentials
            startPolling(flowData.poll.endpoint, flowData.poll.token)
        }
    }

    fun handleScannedQrCode(qrContent: String, context: Context) {
        Log.d("LoginViewModel", "Received scanned QR code: $qrContent")

        val parseResult = NextcloudQrParser.parse(qrContent)
        if (parseResult.isFailure) {
            val errorMsg = parseResult.exceptionOrNull()?.localizedMessage ?: "QR Code 格式無效"
            Log.e("LoginViewModel", "QR code parse failed: $errorMsg")
            _uiState.value = LoginUiState.Error(errorMsg)
            viewModelScope.launch { _toastEvent.emit(errorMsg) }
            return
        }

        val qrData = parseResult.getOrThrow()
        Log.d("LoginViewModel", "Parsed QR Data: server=${qrData.serverUrl}, user=${qrData.username}, hasPassword=${qrData.password != null}, hasToken=${qrData.token != null}")

        when {
            // Case A: QR code contains direct credentials (nc://login/server:...&user:...&password:...)
            !qrData.username.isNullOrBlank() && !qrData.password.isNullOrBlank() -> {
                _uiState.value = LoginUiState.Loading("已從 QR Code 讀取憑證，正在驗證連線...")
                viewModelScope.launch {
                    val test = webDavClient.testConnection(qrData.serverUrl, qrData.username, qrData.password)
                    if (test.isSuccess) {
                        prefsManager.saveCredentials(qrData.serverUrl, qrData.username, qrData.password)
                        _uiState.value = LoginUiState.Success
                        _toastEvent.emit("QR Code 憑證驗證成功！")
                    } else {
                        val err = test.exceptionOrNull()?.localizedMessage ?: "QR Code 憑證無效或伺服器無法連線"
                        Log.e("LoginViewModel", "QR Credentials verification failed", test.exceptionOrNull())
                        _uiState.value = LoginUiState.Error(err)
                        _toastEvent.emit("連線失敗: $err")
                    }
                }
            }

            // Case B: QR code contains server & token (Login Flow v2 token)
            !qrData.token.isNullOrBlank() -> {
                _uiState.value = LoginUiState.Loading("已讀取授權 Token，正在向伺服器換取憑證...")
                val pollEndpoint = "${qrData.serverUrl}/index.php/login/v2/poll"
                startPolling(pollEndpoint, qrData.token)
            }

            // Case C: QR code contains server URL only
            else -> {
                startLoginFlowV2(qrData.serverUrl, context)
            }
        }
    }

    private fun startPolling(endpoint: String, token: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            val pollResult = loginFlowClient.pollForCredentials(
                endpoint = endpoint,
                token = token
            )

            if (pollResult.isSuccess) {
                val creds = pollResult.getOrThrow()
                prefsManager.saveCredentials(creds.server, creds.loginName, creds.appPassword)
                _uiState.value = LoginUiState.Success
                _toastEvent.emit("Nextcloud 授權成功！")
            } else {
                val err = pollResult.exceptionOrNull()?.localizedMessage ?: "授權超時或失敗"
                Log.e("LoginViewModel", "Polling failed: $err")
                _uiState.value = LoginUiState.Error(err)
                _toastEvent.emit(err)
            }
        }
    }

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }
}
