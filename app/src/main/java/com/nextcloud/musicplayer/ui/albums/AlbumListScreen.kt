package com.nextcloud.musicplayer.ui.albums

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    viewModel: AlbumListViewModel,
    onAlbumClick: (albumId: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val albums by viewModel.filteredAlbums.collectAsState()
    val isGridMode by viewModel.isGridMode.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isFavoriteFilterActive by viewModel.isFavoriteFilterActive.collectAsState()

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

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Nextcloud 音樂庫") },
                windowInsets = WindowInsets(0.dp),
                actions = {
                    IconButton(
                        onClick = { viewModel.syncLibrary() },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "同步 WebDAV")
                        }
                    }

                    // 模組 2：我的最愛過濾按鈕
                    IconButton(onClick = { viewModel.toggleFavoriteFilter() }) {
                        Icon(
                            imageVector = if (isFavoriteFilterActive) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavoriteFilterActive) "顯示全部專輯" else "僅顯示我的最愛",
                            tint = if (isFavoriteFilterActive) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }

                    IconButton(onClick = { viewModel.toggleGridMode() }) {
                        Icon(
                            if (isGridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = "切換檢視"
                        )
                    }

                    // 模組 4：齒輪設定按鈕
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(if (isFavoriteFilterActive) "搜尋最愛專輯或歌手..." else "搜尋專輯或歌手...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 模組 3：我的最愛專屬隨機播放列 (Shuffle All Favorites)
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
                                text = "點擊各專輯卡片上的愛心圖示即可加入收藏",
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
                                text = if (searchQuery.isNotEmpty()) "查無相關專輯" else "目前音樂庫為空",
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
                    if (isGridMode) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumGridItem(
                                    album = album,
                                    onToggleFavorite = { viewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                    onClick = { onAlbumClick(album.id) }
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
                                    onToggleFavorite = { viewModel.toggleAlbumFavorite(album.id, album.isFavorite) },
                                    onClick = { onAlbumClick(album.id) }
                                )
                            }
                        }
                    }

                    // 需求 1：快速捲動軸（Fast Scrollbar，含可拖曳 Thumb 與首字氣泡）
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
}

@Composable
fun AlbumGridItem(
    album: AlbumEntity,
    isSelected: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
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
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
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

                // 模組 2：單張專輯快速收藏切換按鈕（無背景圓圈）
                if (onToggleFavorite != null) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(36.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(24.dp)
                        ) {
                            // 陰影層：確保在淺色或白色封面上愛心邊框依然清晰可見
                            Icon(
                                imageVector = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = Color.Black.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .size(22.dp)
                                    .offset(x = 1.dp, y = 1.dp)
                            )
                            Icon(
                                imageVector = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (album.isFavorite) "取消最愛" else "加入最愛",
                                tint = if (album.isFavorite) MaterialTheme.colorScheme.error else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${album.trackCount} 首曲目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AlbumListItem(
    album: AlbumEntity,
    isSelected: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${album.trackCount} 首歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 模組 2：單張專輯收藏切換
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
