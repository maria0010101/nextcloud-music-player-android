package com.nextcloud.musicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nextcloud.musicplayer.ui.albums.AlbumListScreen
import com.nextcloud.musicplayer.ui.albums.AlbumListViewModel
import com.nextcloud.musicplayer.ui.auth.LoginScreen
import com.nextcloud.musicplayer.ui.auth.LoginViewModel
import com.nextcloud.musicplayer.ui.detail.AlbumDetailScreen
import com.nextcloud.musicplayer.ui.detail.AlbumDetailViewModel
import com.nextcloud.musicplayer.ui.player.MiniPlayerBar
import com.nextcloud.musicplayer.ui.player.PlayerScreen
import com.nextcloud.musicplayer.ui.scan.QrScannerScreen
import com.nextcloud.musicplayer.ui.theme.NextcloudMusicTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @OptIn(ExperimentalMaterial3Api::class)
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
        val webDavClient = app.webDavClient
        val loginFlowClient = app.loginFlowClient
        val qrLoginManager = app.qrLoginManager
        val repository = app.musicRepository
        val playerController = app.playerController

        setContent {
            NextcloudMusicTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val startDestination = if (prefs.hasCredentials()) "albums" else "login"

                var isPlayerSheetVisible by remember { mutableStateOf(false) }
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                val playbackError by playerController.playbackError.collectAsState()
                LaunchedEffect(playbackError) {
                    playbackError?.let { err ->
                        snackbarHostState.showSnackbar(err)
                        playerController.clearPlaybackError()
                    }
                }

                var currentRoute by remember { mutableStateOf(startDestination) }
                LaunchedEffect(navController) {
                    navController.addOnDestinationChangedListener { _, destination, _ ->
                        currentRoute = destination.route ?: ""
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            if (currentRoute.startsWith("albums") || currentRoute.startsWith("album_detail")) {
                                MiniPlayerBar(
                                    playerController = playerController,
                                    onClick = { isPlayerSheetVisible = true }
                                )
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable("login") {
                                val loginViewModel = remember {
                                    LoginViewModel(prefs, webDavClient, loginFlowClient, qrLoginManager)
                                }
                                LoginScreen(
                                    viewModel = loginViewModel,
                                    onLoginSuccess = {
                                        navController.navigate("albums") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    },
                                    onOpenQrScanner = {
                                        navController.navigate("qr_scanner")
                                    }
                                )
                            }

                            composable("qr_scanner") {
                                val loginViewModel = remember {
                                    LoginViewModel(prefs, webDavClient, loginFlowClient, qrLoginManager)
                                }
                                QrScannerScreen(
                                    onQrCodeDetected = { scannedText ->
                                        loginViewModel.handleScannedQrCode(scannedText, this@MainActivity)
                                        navController.popBackStack()
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable("albums") {
                                val albumViewModel = remember {
                                    AlbumListViewModel(repository, prefs)
                                }
                                AlbumListScreen(
                                    viewModel = albumViewModel,
                                    onAlbumClick = { albumId ->
                                        val encoded = URLEncoder.encode(albumId, "UTF-8")
                                        navController.navigate("album_detail/$encoded")
                                    },
                                    onLogout = {
                                        navController.navigate("login") {
                                            popUpTo("albums") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                route = "album_detail/{albumId}",
                                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val rawId = backStackEntry.arguments?.getString("albumId") ?: ""
                                val albumId = URLDecoder.decode(rawId, "UTF-8")
                                val detailViewModel = remember(albumId) {
                                    AlbumDetailViewModel(albumId, repository, playerController, applicationContext)
                                }
                                AlbumDetailScreen(
                                    viewModel = detailViewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    // 模組 2：滑出式全螢幕播放視窗 (Bottom Sheet)
                    if (isPlayerSheetVisible) {
                        ModalBottomSheet(
                            onDismissRequest = { isPlayerSheetVisible = false },
                            sheetState = sheetState
                        ) {
                            PlayerScreen(
                                playerController = playerController,
                                onDismiss = { isPlayerSheetVisible = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as? NextcloudMusicApp)?.playerController?.disconnect()
    }
}
