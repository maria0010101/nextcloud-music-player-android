package com.nextcloud.musicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.nextcloud.musicplayer.ui.adaptive.AdaptiveHomeScreen
import com.nextcloud.musicplayer.ui.auth.LoginScreen
import com.nextcloud.musicplayer.ui.auth.LoginViewModel
import com.nextcloud.musicplayer.ui.theme.NextcloudMusicTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+ for Foreground Service notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val app = application as NextcloudMusicApp
        val prefs = app.securePreferencesManager
        val loginFlowClient = app.loginFlowClient

        // 確保播放控制器保持連線
        app.playerController.connect()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            NextcloudMusicTheme {
                var isLoggedIn by remember { mutableStateOf(prefs.hasCredentials()) }

                if (isLoggedIn) {
                    AdaptiveHomeScreen(
                        widthSizeClass = windowSizeClass.widthSizeClass,
                        app = app,
                        onLogout = {
                            prefs.clear()
                            isLoggedIn = false
                        }
                    )
                } else {
                    val loginViewModel = remember {
                        LoginViewModel(prefs, loginFlowClient, app.webDavClient)
                    }
                    LoginScreen(
                        viewModel = loginViewModel,
                        onLoginSuccess = {
                            isLoggedIn = true
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 僅在 Activity 真正關閉（非螢幕旋轉導致之重建）時釋放連線
        if (isFinishing) {
            (application as? NextcloudMusicApp)?.playerController?.disconnect()
        }
    }
}
