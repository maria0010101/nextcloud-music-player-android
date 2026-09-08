package com.nextcloud.musicplayer.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.ui.albums.AlbumListScreen
import com.nextcloud.musicplayer.ui.albums.AlbumListViewModel
import com.nextcloud.musicplayer.ui.detail.AlbumDetailScreen
import com.nextcloud.musicplayer.ui.detail.AlbumDetailViewModel
import com.nextcloud.musicplayer.ui.player.MiniPlayerBar
import com.nextcloud.musicplayer.ui.player.PlayerScreen
import com.nextcloud.musicplayer.ui.settings.SettingsScreen
import com.nextcloud.musicplayer.ui.settings.SettingsViewModel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 頂層自適應切換容器 (AdaptiveHomeScreen)
 * 1. 平板 / 橫向大螢幕 (Width >= 840dp，Expanded)：啟用全螢幕水平三欄並排佈局 (TabletThreePaneLayout)，
 *    停用底部彈出式播放視窗，由最右欄常態呈現播放控制與中繼資訊。
 * 2. 手機 / 直向模式 (Width < 840dp，Compact / Medium)：維持原單欄導航與滑出式 Mini / Full Player。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveHomeScreen(
    widthSizeClass: WindowWidthSizeClass,
    app: NextcloudMusicApp,
    onLogout: () -> Unit
) {
    val repository = app.musicRepository
    val prefs = app.securePreferencesManager
    val playerController = app.playerController
    val coverSearchRepo = app.coverSearchRepository
    val coverManager = app.coverManager
    val settingsDataStore = app.appSettingsDataStore
    val cacheManager = app.playbackCacheManager
    val dataStoreManager = app.dataStoreManager

    // 共用 AlbumListViewModel，確保螢幕旋轉或版面切換時維持搜尋狀態與已載入資料
    val albumListViewModel = remember {
        AlbumListViewModel(repository, prefs, app)
    }

    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        // ==========================================
        // 【平板／大螢幕模式 (Expanded, Width >= 840dp)】
        // ==========================================
        TabletThreePaneLayout(
            albumListViewModel = albumListViewModel,
            playerController = playerController,
            repository = repository,
            coverSearchRepository = coverSearchRepo,
            coverManager = coverManager,
            settingsDataStore = settingsDataStore,
            prefsManager = prefs,
            cacheManager = cacheManager,
            dataStoreManager = dataStoreManager,
            onLogout = onLogout
        )
    } else {
        // ==========================================
        // 【手機／直向模式 (Compact / Medium, Width < 840dp)】
        // ==========================================
        PhoneModeLayout(
            albumListViewModel = albumListViewModel,
            app = app,
            onLogout = onLogout
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneModeLayout(
    albumListViewModel: AlbumListViewModel,
    app: NextcloudMusicApp,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val repository = app.musicRepository
    val prefs = app.securePreferencesManager
    val playerController = app.playerController
    val coverSearchRepo = app.coverSearchRepository
    val coverManager = app.coverManager
    val settingsDataStore = app.appSettingsDataStore
    val cacheManager = app.playbackCacheManager
    val dataStoreManager = app.dataStoreManager

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    var isPlayerSheetVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val playbackError by playerController.playbackError.collectAsState()
    LaunchedEffect(playbackError) {
        playbackError?.let { err ->
            snackbarHostState.showSnackbar(err)
            playerController.clearPlaybackError()
        }
    }

    var currentRoute by remember { mutableStateOf("albums") }
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
                startDestination = "albums",
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                // 1. 專輯列表
                composable("albums") {
                    AlbumListScreen(
                        viewModel = albumListViewModel,
                        onAlbumClick = { albumId ->
                            val encoded = URLEncoder.encode(albumId, "UTF-8")
                            navController.navigate("album_detail/$encoded")
                        },
                        onOpenSettings = {
                            navController.navigate("settings")
                        }
                    )
                }

                // 2. 專輯曲目詳情
                composable(
                    route = "album_detail/{albumId}",
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val rawId = backStackEntry.arguments?.getString("albumId") ?: ""
                    val albumId = URLDecoder.decode(rawId, "UTF-8")
                    val detailViewModel = remember(albumId) {
                        AlbumDetailViewModel(
                            albumId = albumId,
                            repository = repository,
                            playerController = playerController,
                            coverSearchRepository = coverSearchRepo,
                            coverManager = coverManager,
                            context = context
                        )
                    }
                    AlbumDetailScreen(
                        viewModel = detailViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                // 3. 設定畫面
                composable("settings") {
                    val settingsViewModel = remember {
                        SettingsViewModel(
                            repository = repository,
                            prefsManager = prefs,
                            settingsDataStore = settingsDataStore,
                            cacheManager = cacheManager,
                            dataStoreManager = dataStoreManager,
                            context = app
                        )
                    }
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = { navController.popBackStack() },
                        onLogout = onLogout
                    )
                }
            }
        }

        // 滑出式全螢幕播放視窗 (Bottom Sheet)
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
