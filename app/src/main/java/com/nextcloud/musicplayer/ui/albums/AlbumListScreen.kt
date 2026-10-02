package com.nextcloud.musicplayer.ui.albums

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import com.nextcloud.musicplayer.ui.common.conditionalMarquee
import com.nextcloud.musicplayer.ui.playlist.AddToPlaylistDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    viewModel: AlbumListViewModel,
    onAlbumClick: (albumId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylists: () -> Unit = {}
) {
    val context = LocalContext.current
    val albums by viewModel.filteredAlbums.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isFavoriteFilterActive by viewModel.isFavoriteFilterActive.collectAsState()
    val enableAlbumTitleMarquee by viewModel.enableAlbumTitleMarquee.collectAsState()

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    var selectedAlbumForAction by remember { mutableStateOf<AlbumEntity?>(null) }
    var albumForAddToPlaylist by remember { mutableStateOf<AlbumEntity?>(null) }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.syncCompletedEvent.collectLatest { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSearchActive) {
                // 需求 1：按下搜尋按鈕後顯示之搜尋輸入頂部列
                TopAppBar(
                    windowInsets = WindowInsets(0.dp),
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "結束搜尋")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text(if (isFavoriteFilterActive) "搜尋最愛專輯或歌手..." else "搜尋專輯或歌手...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除關鍵字")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = "音樂庫",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    windowInsets = WindowInsets(0.dp),
                    actions = {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                            // 需求 1：搜尋功能按鈕
                            IconButton(
                                onClick = { isSearchActive = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "搜尋專輯或歌手",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // WebDAV 同步
                            IconButton(
                                onClick = { viewModel.syncLibrary() },
                                enabled = !isSyncing,
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = "同步 WebDAV",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // 我的最愛過濾
                            IconButton(
                                onClick = { viewModel.toggleFavoriteFilter() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavoriteFilterActive) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFavoriteFilterActive) "顯示全部專輯" else "僅顯示我的最愛",
                                    tint = if (isFavoriteFilterActive) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 需求 2：自選播放清單列表入口
                            IconButton(
                                onClick = onOpenPlaylists,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "自訂播放清單",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 需求 5：多欄位顯示模式 (列表、1欄、2欄、3欄、4欄)
                            Box {
                                var showViewModeMenu by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showViewModeMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    val currentIcon = when (viewMode) {
                                        AlbumViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                                        AlbumViewMode.GRID_1 -> Icons.Default.ViewAgenda
                                        AlbumViewMode.GRID_2 -> Icons.Default.GridView
                                        AlbumViewMode.GRID_3 -> Icons.Default.Apps
                                        AlbumViewMode.GRID_4 -> Icons.Default.ViewCompact
                                    }
                                    Icon(
                                        currentIcon,
                                        contentDescription = "切換排版模式",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showViewModeMenu,
                                    onDismissRequest = { showViewModeMenu = false }
                                ) {
                                    AlbumViewMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.title) },
                                            trailingIcon = {
                                                if (viewMode == mode) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                val itemIcon = when (mode) {
                                                    AlbumViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                                                    AlbumViewMode.GRID_1 -> Icons.Default.ViewAgenda
                                                    AlbumViewMode.GRID_2 -> Icons.Default.GridView
                                                    AlbumViewMode.GRID_3 -> Icons.Default.Apps
                                                    AlbumViewMode.GRID_4 -> Icons.Default.ViewCompact
                                                }
                                                Icon(itemIcon, contentDescription = null)
                                            },
                                            onClick = {
                                                showViewModeMenu = false
                                                viewModel.setViewMode(mode)
                                            }
                                        )
                                    }
                                }
                            }

                            // 設定按鈕
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "設定",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 我的最愛專屬隨機播放列 (Shuffle All Favorites)
            if (isFavoriteFilterActive) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "最愛專輯 (${albums.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.playFavoriteTracksShuffled(
                                    onNoTracks = {
                                        Toast.makeText(context, "尚未收藏任何專輯或最愛專輯內無歌曲", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("隨機播放最愛歌曲", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (albums.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFavoriteFilterActive) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "查無符合的最愛專輯" else "尚未收藏任何最愛專輯",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "長按專輯或點擊愛心圖示即可加入收藏",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { viewModel.toggleFavoriteFilter() }) {
                                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("顯示全部專輯")
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "查無符合關鍵字「$searchQuery」的專輯" else "目前音樂庫為空",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "請前往「設定」挑選 Nextcloud 上的音樂專用目錄並執行掃描",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("前往設定頁面")
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (viewMode.isGrid) {
                        val spacing = when (viewMode.columns) {
                            1 -> 14.dp
                            3 -> 10.dp
                            4 -> 8.dp
                            else -> 12.dp
                        }
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(viewMode.columns),
                            contentPadding = PaddingValues(spacing),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumGridItem(
                                    album = album,
                                    columns = viewMode.columns,
                                    enableMarquee = enableAlbumTitleMarquee,
                                    onToggleFavorite = { viewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                    onClick = { onAlbumClick(album.id) },
                                    onLongClick = { selectedAlbumForAction = album }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumListItem(
                                    album = album,
                                    enableMarquee = enableAlbumTitleMarquee,
                                    onToggleFavorite = { viewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                    onClick = { onAlbumClick(album.id) },
                                    onLongClick = { selectedAlbumForAction = album }
                                )
                            }
                        }
                    }

                    // 快速捲動軸（Fast Scrollbar）
                    FastScrollbar(
                        totalItemCount = albums.size,
                        firstVisibleIndex = if (viewMode.isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex,
                        isScrollInProgress = if (viewMode.isGrid) gridState.isScrollInProgress else listState.isScrollInProgress,
                        onScrollToItem = { targetIndex ->
                            coroutineScope.launch {
                                if (viewMode.isGrid) {
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

    // 需求 3 & 4：長按專輯彈出動作 Bottom Sheet
    selectedAlbumForAction?.let { album ->
        AlbumActionBottomSheet(
            album = album,
            onDismiss = { selectedAlbumForAction = null },
            onAppendToQueue = {
                viewModel.appendAlbumToQueue(album) { count ->
                    Toast.makeText(context, "已將《${album.name}》（$count 首）加入播放序列", Toast.LENGTH_SHORT).show()
                }
            },
            onClearAndPlay = {
                viewModel.playAlbumNow(album) { count ->
                    Toast.makeText(context, "已清除序列並開始播放《${album.name}》（$count 首）", Toast.LENGTH_SHORT).show()
                }
            },
            onAddToPlaylist = {
                albumForAddToPlaylist = album
            },
            onToggleFavorite = {
                viewModel.toggleAlbumFavorite(album.id, album.isFavorite)
                val msg = if (!album.isFavorite) "已加入最愛專輯" else "已取消最愛收藏"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onViewAlbumDetail = {
                onAlbumClick(album.id)
            }
        )
    }

    // 需求 2：加入自選播放清單 Dialog
    albumForAddToPlaylist?.let { album ->
        AddToPlaylistDialog(
            playlists = playlists,
            targetTitle = "專輯《${album.name}》",
            onDismiss = { albumForAddToPlaylist = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addAlbumToPlaylist(album.id, playlistId) { count ->
                    Toast.makeText(context, "已將 $count 首歌曲加入播放清單", Toast.LENGTH_SHORT).show()
                }
            },
            onCreateNewPlaylist = { name ->
                viewModel.createPlaylistWithAlbum(name, album.id) { count ->
                    Toast.makeText(context, "已建立「$name」並加入 $count 首歌曲", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridItem(
    album: AlbumEntity,
    columns: Int = 2,
    isSelected: Boolean = false,
    enableMarquee: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val cornerRadius = when {
        columns == 1 -> 14.dp
        columns >= 4 -> 6.dp
        columns == 3 -> 8.dp
        else -> 12.dp
    }

    val contentPadding = when {
        columns == 1 -> 12.dp
        columns >= 4 -> 4.dp
        columns == 3 -> 6.dp
        else -> 10.dp
    }

    val titleFontSize = when {
        columns == 1 -> 16.sp
        columns >= 4 -> 11.sp
        columns == 3 -> 12.sp
        else -> 14.sp
    }

    val titleFontWeight = if (columns == 1) FontWeight.SemiBold else FontWeight.Medium

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(cornerRadius),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column {
            Box {
                AsyncImage(
                    model = album.coverUrl,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                )
                if (isSelected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已選取",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp)
                        )
                    }
                }

                // 單張專輯快速收藏切換按鈕
                if (onToggleFavorite != null && columns <= 3) {
                    val iconBtnSize = if (columns == 3) 30.dp else 36.dp
                    val iconSize = if (columns == 3) 18.dp else 22.dp
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(iconBtnSize)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(iconSize)
                        ) {
                            Icon(
                                imageVector = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = Color.Black.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .size(iconSize)
                                    .offset(x = 1.dp, y = 1.dp)
                            )
                            Icon(
                                imageVector = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (album.isFavorite) "取消最愛" else "加入最愛",
                                tint = if (album.isFavorite) MaterialTheme.colorScheme.error else Color.White,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(contentPadding)) {
                Text(
                    text = album.name,
                    fontSize = titleFontSize,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = titleFontWeight,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (enableMarquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .conditionalMarquee(enableMarquee)
                )
                if (columns <= 3) {
                    Spacer(modifier = Modifier.height(if (columns == 1) 4.dp else 2.dp))
                    Text(
                        text = "${album.trackCount} 首曲目",
                        fontSize = if (columns == 1) 13.sp else 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumListItem(
    album: AlbumEntity,
    isSelected: Boolean = false,
    enableMarquee: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = album.coverUrl,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    fontSize = 14.sp,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (enableMarquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .conditionalMarquee(enableMarquee)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${album.trackCount} 首歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (album.isFavorite) "取消最愛" else "加入最愛",
                        tint = if (album.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已選取",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
