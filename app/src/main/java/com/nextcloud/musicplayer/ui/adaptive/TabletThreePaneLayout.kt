package com.nextcloud.musicplayer.ui.adaptive

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.nextcloud.musicplayer.playback.PlaybackCacheManager
import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import com.nextcloud.musicplayer.core.settings.DataStoreManager
import com.nextcloud.musicplayer.data.repository.CoverManager
import com.nextcloud.musicplayer.data.repository.CoverSearchRepository
import com.nextcloud.musicplayer.data.repository.MusicRepository
import com.nextcloud.musicplayer.playback.PlaybackState
import com.nextcloud.musicplayer.playback.PlayerController
import com.nextcloud.musicplayer.ui.albums.AlbumGridItem
import com.nextcloud.musicplayer.ui.albums.AlbumListItem
import com.nextcloud.musicplayer.ui.albums.AlbumListViewModel
import kotlinx.coroutines.flow.collectLatest
import com.nextcloud.musicplayer.ui.albums.FastScrollbar
import com.nextcloud.musicplayer.ui.detail.AlbumDetailViewModel
import com.nextcloud.musicplayer.ui.detail.CoverSearchBottomSheet
import com.nextcloud.musicplayer.ui.detail.TrackListItem
import com.nextcloud.musicplayer.ui.settings.SettingsScreen
import com.nextcloud.musicplayer.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletThreePaneLayout(
    albumListViewModel: AlbumListViewModel,
    playerController: PlayerController,
    repository: MusicRepository,
    coverSearchRepository: CoverSearchRepository,
    coverManager: CoverManager,
    settingsDataStore: AppSettingsDataStore,
    prefsManager: SecurePreferencesManager,
    cacheManager: PlaybackCacheManager,
    dataStoreManager: DataStoreManager,
    onOpenSoundEffects: () -> Unit = {},
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. 專輯列表狀態
    val albums by albumListViewModel.filteredAlbums.collectAsState()
    val isGridMode by albumListViewModel.isGridMode.collectAsState()
    val isSyncing by albumListViewModel.isSyncing.collectAsState()
    val syncMessage by albumListViewModel.syncMessage.collectAsState()
    val searchQuery by albumListViewModel.searchQuery.collectAsState()
    val isFavoriteFilterActive by albumListViewModel.isFavoriteFilterActive.collectAsState()

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    // 2. 播放控制器狀態 (跨欄共享)
    val currentTrack by playerController.currentTrack.collectAsState()
    val isPlaying by playerController.isPlaying.collectAsState()
    val playbackState by playerController.playbackState.collectAsState()
    val positionMs by playerController.currentPositionMs.collectAsState()
    val durationMs by playerController.durationMs.collectAsState()
    val audioSpecs by playerController.audioSpecs.collectAsState()
    val shuffleEnabled by playerController.shuffleModeEnabled.collectAsState()
    val repeatMode by playerController.repeatMode.collectAsState()

    // 3. 當前選取的專輯 (中欄連動)
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCoverSearchSheet by remember { mutableStateOf(false) }
    var showZoomedArtworkDialog by remember { mutableStateOf(false) }

    // 預設自動選取：若尚未選取，優先選取目前正在播放的專輯，或清單第一張專輯
    LaunchedEffect(albums) {
        if (selectedAlbumId == null && albums.isNotEmpty()) {
            val playingAlbumId = currentTrack?.albumId
            val target = albums.find { it.id == playingAlbumId } ?: albums.first()
            selectedAlbumId = target.id
        }
    }

    LaunchedEffect(Unit) {
        albumListViewModel.syncCompletedEvent.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 中欄綁定之 AlbumDetailViewModel (當 selectedAlbumId 改變時自動建立)
    val detailViewModel = remember(selectedAlbumId) {
        selectedAlbumId?.let { id ->
            AlbumDetailViewModel(
                albumId = id,
                repository = repository,
                playerController = playerController,
                coverSearchRepository = coverSearchRepository,
                coverManager = coverManager,
                context = context
            )
        }
    }

    val selectedAlbum by (detailViewModel?.album ?: MutableStateFlow(null)).collectAsState()
    val albumTracks by (detailViewModel?.tracks ?: MutableStateFlow(emptyList())).collectAsState()
    val downloadStatus by (detailViewModel?.downloadStatus ?: MutableStateFlow(null)).collectAsState()

    // 進度條 Seek 控制狀態
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPosition by remember { mutableFloatStateOf(0f) }
    val currentSliderValue = if (isSeeking) {
        seekSliderPosition
    } else {
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    // 主三欄式容器
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        // ==========================================
        // 【左欄：專輯列表 (Album List Pane)】 (weight: 1.1f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 左欄頂部：標題與功能列
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Nextcloud 音樂庫",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 同步按鈕
                        IconButton(
                            onClick = { albumListViewModel.syncLibrary() },
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "同步 WebDAV")
                            }
                        }

                        // 模組 2：我的最愛過濾按鈕
                        IconButton(onClick = { albumListViewModel.toggleFavoriteFilter() }) {
                            Icon(
                                imageVector = if (isFavoriteFilterActive) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFavoriteFilterActive) "顯示全部專輯" else "僅顯示我的最愛",
                                tint = if (isFavoriteFilterActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 切換網格/列表模式
                        IconButton(onClick = { albumListViewModel.toggleGridMode() }) {
                            Icon(
                                if (isGridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = "切換檢視"
                            )
                        }

                        // 設定按鈕
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                    }
                }

                // 搜尋列
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { albumListViewModel.setSearchQuery(it) },
                    placeholder = { Text(if (isFavoriteFilterActive) "搜尋最愛專輯或歌手..." else "搜尋專輯或歌手...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { albumListViewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // 模組 3：我的最愛專屬隨機播放列 (Shuffle All Favorites)
                if (isFavoriteFilterActive) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "最愛 (${albums.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            FilledTonalButton(
                                onClick = {
                                    albumListViewModel.playFavoriteTracksShuffled(
                                        onNoTracks = {
                                            Toast.makeText(context, "尚未收藏任何專輯或最愛專輯內無歌曲", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("隨機播放最愛", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // 專輯內容網格 / 清單
                Box(modifier = Modifier.fillMaxSize()) {
                    if (albums.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (isFavoriteFilterActive) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) "查無符合的最愛專輯" else "尚未收藏任何最愛專輯",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = { albumListViewModel.toggleFavoriteFilter() }) {
                                        Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("顯示全部", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) "查無相關專輯" else "目前音樂庫為空",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            }
                        }
                    } else {
                        if (isGridMode) {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 120.dp),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumGridItem(
                                        album = album,
                                        isSelected = (album.id == selectedAlbumId),
                                        onToggleFavorite = { albumListViewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                        onClick = { selectedAlbumId = album.id }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumListItem(
                                        album = album,
                                        isSelected = (album.id == selectedAlbumId),
                                        onToggleFavorite = { albumListViewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                        onClick = { selectedAlbumId = album.id }
                                    )
                                }
                            }
                        }

                        // 快速滾動條 (FastScrollbar)
                        FastScrollbar(
                            totalItemCount = albums.size,
                            firstVisibleIndex = if (isGridMode) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex,
                            isScrollInProgress = if (isGridMode) gridState.isScrollInProgress else listState.isScrollInProgress,
                            onScrollToItem = { targetIndex ->
                                coroutineScope.launch {
                                    if (isGridMode) {
                                        gridState.scrollToItem(targetIndex)
                                    } else {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            },
                            getIndicatorText = { index ->
                                val name = albums.getOrNull(index)?.name.orEmpty()
                                val firstChar = name.firstOrNull() ?: '#'
                                if (firstChar.isLetter()) firstChar.uppercaseChar().toString()
                                else if (firstChar.isDigit()) firstChar.toString()
                                else "#"
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(top = 8.dp, bottom = 8.dp, end = 2.dp)
                        )
                    }
                }
            }
        }

        // 分隔線 1
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // ==========================================
        // 【中欄：曲目清單 (Track List Pane)】 (weight: 1.0f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (selectedAlbum == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "請從左側選取一張專輯",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val currentAlbum = selectedAlbum!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    // 專輯概要標題卡
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!currentAlbum.coverUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = currentAlbum.coverUrl,
                                            contentDescription = currentAlbum.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Album,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentAlbum.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "共 ${albumTracks.size} 首曲目",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // 模組 2：最愛收藏切換按鈕
                                IconButton(onClick = { detailViewModel?.toggleFavorite() }) {
                                    Icon(
                                        imageVector = if (currentAlbum.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (currentAlbum.isFavorite) "取消收藏" else "加入收藏",
                                        tint = if (currentAlbum.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 快捷播放按鍵群
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { detailViewModel?.playAll(shuffle = false) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("全部播放")
                                }

                                OutlinedButton(
                                    onClick = { detailViewModel?.playAll(shuffle = true) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("隨機播放")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 操作列：線上更換封面與下載整張專輯
                            val isDownloading = downloadStatus != null && downloadStatus != "已完成下載"
                            val isDownloaded = currentAlbum.isDownloaded || downloadStatus == "已完成下載"

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { showCoverSearchSheet = true },
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("線上更換封面", style = MaterialTheme.typography.labelMedium)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { detailViewModel?.startDownloadAlbum(context) },
                                    enabled = !isDownloading
                                ) {
                                    if (isDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else if (isDownloaded) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "已下載至本機", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = "下載整張專輯", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }

                    // 曲目列表項
                    itemsIndexed(albumTracks, key = { _, track -> track.id }) { index, track ->
                        val isThisTrackPlaying = (currentTrack?.id == track.id) && isPlaying
                        TrackListItem(
                            index = index + 1,
                            track = track,
                            isPlaying = isThisTrackPlaying,
                            onClick = { detailViewModel?.playTrack(index) }
                        )
                    }
                }
            }
        }

        // 分隔線 2
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // ==========================================
        // 【右欄：現正播放與資訊 (Now Playing & Info Pane)】 (weight: 1.0f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompactHeight = maxHeight < 520.dp
                val coverSize = if (isCompactHeight) 140.dp else 240.dp
                val contentPadding = if (isCompactHeight) 12.dp else 20.dp
                val rightScrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rightScrollState)
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (isCompactHeight) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly
                ) {
                    // 1. 標頭與音效設定按鈕
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "現正播放",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = onOpenSoundEffects) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "音效與等化器",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 2. 大尺寸專輯封面圖 (支援點擊放大與緩衝覆蓋層)
                    val effectiveCoverUrl = currentTrack?.coverUrl ?: selectedAlbum?.coverUrl
                    Box(
                        modifier = Modifier
                            .size(coverSize)
                            .clip(RoundedCornerShape(if (isCompactHeight) 14.dp else 20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !effectiveCoverUrl.isNullOrBlank()) {
                                showZoomedArtworkDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!effectiveCoverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = effectiveCoverUrl,
                                contentDescription = currentTrack?.title ?: "專輯封面",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(if (isCompactHeight) 56.dp else 96.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 緩衝動畫覆蓋層
                        if (playbackState is PlaybackState.Buffering) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.65f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = (playbackState as PlaybackState.Buffering).message,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 3. 曲目資訊與常態音訊規格 (Audio Hardware Specs)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = currentTrack?.title ?: "尚未播放歌曲",
                            style = if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = selectedAlbum?.name ?: currentTrack?.format ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // 規格技術資訊晶片
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val displaySpecs = audioSpecs.ifBlank { currentTrack?.format ?: "FLAC" }
                            SuggestionChip(
                                onClick = {},
                                label = { Text(displaySpecs, style = MaterialTheme.typography.labelSmall) }
                            )

                            if (currentTrack?.isDownloaded == true) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("本機離線", style = MaterialTheme.typography.labelSmall) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            }
                        }
                    }

                    // 4. 進度條（Slider/SeekBar）與時間戳
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = currentSliderValue,
                            onValueChange = {
                                isSeeking = true
                                seekSliderPosition = it
                            },
                            onValueChangeFinished = {
                                val targetMs = (seekSliderPosition * durationMs).toLong()
                                playerController.seekTo(targetMs)
                                isSeeking = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val currentDisplayedMs = if (isSeeking) {
                                (seekSliderPosition * durationMs).toLong()
                            } else {
                                positionMs
                            }
                            Text(
                                text = formatDuration(currentDisplayedMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDuration(durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 5. 播放控制按鍵群
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 隨機播放
                        IconButton(onClick = { playerController.toggleShuffle() }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "隨機播放",
                                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 上一首
                        IconButton(onClick = { playerController.skipToPrevious() }) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                modifier = Modifier.size(if (isCompactHeight) 28.dp else 34.dp)
                            )
                        }

                        // 播放 / 暫停 FAB
                        FloatingActionButton(
                            onClick = { playerController.togglePlayPause() },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(if (isCompactHeight) 52.dp else 64.dp)
                        ) {
                            if (playbackState is PlaybackState.Buffering) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "暫停" else "播放",
                                    modifier = Modifier.size(if (isCompactHeight) 28.dp else 36.dp)
                                )
                            }
                        }

                        // 下一首
                        IconButton(onClick = { playerController.skipToNext() }) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                modifier = Modifier.size(if (isCompactHeight) 28.dp else 34.dp)
                            )
                        }

                        // 循環播放模式
                        IconButton(onClick = { playerController.cycleRepeatMode() }) {
                            val (icon, tint) = when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to MaterialTheme.colorScheme.primary
                                Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to MaterialTheme.colorScheme.primary
                                else -> Icons.Default.Repeat to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(imageVector = icon, contentDescription = "循環播放", tint = tint)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // 模態視窗：線上搜尋封面 BottomSheet
    // ==========================================
    if (showCoverSearchSheet && detailViewModel != null) {
        CoverSearchBottomSheet(
            initialQuery = selectedAlbum?.name ?: "",
            onDismissRequest = { showCoverSearchSheet = false },
            viewModel = detailViewModel
        )
    }

    // ==========================================
    // 模態視窗：大尺寸封面放大檢視 Dialog
    // ==========================================
    if (showZoomedArtworkDialog && !currentTrack?.coverUrl.isNullOrBlank()) {
        Dialog(onDismissRequest = { showZoomedArtworkDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .wrapContentSize()
                    .padding(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    AsyncImage(
                        model = currentTrack?.coverUrl,
                        contentDescription = "放大專輯封面",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(380.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentTrack?.title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { showZoomedArtworkDialog = false }) {
                        Text("關閉")
                    }
                }
            }
        }
    }

    // ==========================================
    // 模態視窗：平板設定畫面 Dialog
    // ==========================================
    if (showSettingsDialog) {
        Dialog(
            onDismissRequest = { showSettingsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp
            ) {
                val currentContext = LocalContext.current
                val settingsViewModel = remember {
                    SettingsViewModel(
                        repository = repository,
                        prefsManager = prefsManager,
                        settingsDataStore = settingsDataStore,
                        cacheManager = cacheManager,
                        dataStoreManager = dataStoreManager,
                        context = currentContext
                    )
                }
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenSoundEffects = onOpenSoundEffects,
                    onBack = { showSettingsDialog = false },
                    onLogout = {
                        showSettingsDialog = false
                        onLogout()
                    }
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
