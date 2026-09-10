package com.nextcloud.musicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.nextcloud.musicplayer.ui.adaptive.AdaptiveHomeScreen
import com.nextcloud.musicplayer.ui.auth.LoginScreen
import com.nextcloud.musicplayer.ui.auth.LoginViewModel
import com.nextcloud.musicplayer.ui.player.PlayerViewModel
import com.nextcloud.musicplayer.ui.player.VolumeHud
import com.nextcloud.musicplayer.ui.player.VolumeSyncManager
import com.nextcloud.musicplayer.ui.theme.NextcloudMusicTheme

class MainActivity : ComponentActivity() {

    private lateinit var playerViewModel: PlayerViewModel

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
        val volumeSyncManager = VolumeSyncManager(this, app.playerController)

        // 初始化全域 PlayerViewModel（音量管理與 HUD 控制）
        playerViewModel = ViewModelProvider(
            this,
            PlayerViewModel.provideFactory(app.playerController, app.appSettingsDataStore, volumeSyncManager)
        )[PlayerViewModel::class.java]

        // 確保播放控制器保持連線
        app.playerController.connect()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            NextcloudMusicTheme {
                Box(modifier = Modifier.fillMaxSize()) {
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

                    // 根層級懸浮音量控制條（僅在 25 或 50 段模式且按鍵調節時彈出）
                    VolumeHud(
                        playerViewModel = playerViewModel,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val steps = playerViewModel.volumeSteps.value
            if (steps == 25 || steps == 50) {
                val isIncrement = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                playerViewModel.adjustVolume(isIncrement)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val steps = playerViewModel.volumeSteps.value
            if (steps == 25 || steps == 50) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // 啟動／切入前台：對齊系統音量並將系統底座拉滿
        playerViewModel.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        // 退至背景：還原系統原生音量並復原 ExoPlayer 軟體衰減
        if (!isChangingConfigurations) {
            playerViewModel.onAppBackground()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 僅在 Activity 真正關閉（非螢幕旋轉導致之重建）時釋放連線與還原音量
        if (isFinishing) {
            playerViewModel.onAppBackground()
            (application as? NextcloudMusicApp)?.playerController?.disconnect()
        }
    }
}
