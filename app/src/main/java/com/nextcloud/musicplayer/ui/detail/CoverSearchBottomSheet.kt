package com.nextcloud.musicplayer.ui.detail

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextcloud.musicplayer.data.repository.ItunesAlbumItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSearchBottomSheet(
    initialQuery: String,
    onDismissRequest: () -> Unit,
    viewModel: AlbumDetailViewModel
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 模組 1：使用 TextFieldValue 設定游標置於末端，並確保水平捲動自動向右跟隨游標
    var searchTextFieldValue by remember {
        mutableStateOf(TextFieldValue(initialQuery, selection = TextRange(initialQuery.length)))
    }
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val isApplyingCover by viewModel.isApplyingCover.collectAsState()

    var selectedItemForConfirm by remember { mutableStateOf<ItunesAlbumItem?>(null) }
    var localPickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 模組 4：官方 Photo Picker 選擇本機圖片 (無需額外儲存權限)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    localPickedBytes = bytes
                } else {
                    Toast.makeText(context, "無法讀取選取的圖片檔案", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "讀取圖片失敗: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 模組 2：注入 NestedScrollConnection，在 Sheet 達到頂部時就地消費 (Consume) 上向 Overscroll (available.y < 0)
    // 徹底消除外層 Sheet 彈性回彈造成的畫面抖動 (Overscroll Jitter)
    val overscrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return if (available.y < 0f) {
                    Offset(x = 0f, y = available.y)
                } else {
                    Offset.Zero
                }
            }
        }
    }

    // 自動以專輯名稱進行第一次搜尋
    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank() && searchResults.isEmpty()) {
            viewModel.searchCovers(initialQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .nestedScroll(overscrollConnection)
                .padding(horizontal = 16.dp)
        ) {
            // 標題列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ImageSearch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "更換專輯封面",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "關閉")
                }
            }

            // 模組 4 UI 入口：從本機相簿選擇圖片按鈕
            OutlinedButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "從本機相簿選擇圖片",
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 模組 1：搜尋關鍵字輸入框 (水平單行捲動，游標拉到最右側自動滾動不裁切)
            OutlinedTextField(
                value = searchTextFieldValue,
                onValueChange = { searchTextFieldValue = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("輸入專輯或藝人名稱線上搜尋...") },
                singleLine = true,
                maxLines = 1,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchTextFieldValue.text.isNotEmpty()) {
                            IconButton(onClick = {
                                searchTextFieldValue = TextFieldValue("", selection = TextRange.Zero)
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除")
                            }
                        }
                        IconButton(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.searchCovers(searchTextFieldValue.text)
                            },
                            enabled = searchTextFieldValue.text.isNotBlank() && !isSearching
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "搜尋",
                                tint = if (searchTextFieldValue.text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    viewModel.searchCovers(searchTextFieldValue.text)
                }),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 搜尋狀態與候選清單 (Grid)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSearching -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "正在從 iTunes 搜尋高解析度封面...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    searchError != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = searchError ?: "搜尋失敗",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.searchCovers(searchTextFieldValue.text) }) {
                                Text("重試")
                            }
                        }
                    }

                    searchResults.isEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "查無相關專輯封面",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "請嘗試修改上方關鍵字，或點擊上方「從本機相簿選擇圖片」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(searchResults, key = { it.collectionId ?: it.artworkUrl100 ?: it.hashCode() }) { item ->
                                CandidateCoverCard(
                                    item = item,
                                    onClick = { selectedItemForConfirm = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 模組 4：確認彈窗（雙按鈕：寫回雲端 / 僅限本機顯示 - 線上候選項目）
    selectedItemForConfirm?.let { item ->
        CoverConfirmDialog(
            item = item,
            isApplying = isApplyingCover,
            onDismiss = {
                if (!isApplyingCover) {
                    selectedItemForConfirm = null
                }
            },
            onApply = { toCloud ->
                viewModel.applyCover(item, toCloud) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) {
                        selectedItemForConfirm = null
                        onDismissRequest()
                    }
                }
            }
        )
    }

    // 模組 4：確認彈窗（雙按鈕：寫回雲端 / 僅限本機顯示 - 本機相簿選取項目）
    localPickedBytes?.let { bytes ->
        LocalCoverConfirmDialog(
            imageBytes = bytes,
            albumName = viewModel.album.value?.name ?: "音樂專輯",
            isApplying = isApplyingCover,
            onDismiss = {
                if (!isApplyingCover) {
                    localPickedBytes = null
                }
            },
            onApply = { toCloud ->
                viewModel.applyCoverBytes(bytes, toCloud) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) {
                        localPickedBytes = null
                        onDismissRequest()
                    }
                }
            }
        )
    }
}

@Composable
private fun CandidateCoverCard(
    item: ItunesAlbumItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            AsyncImage(
                model = item.highResArtworkUrl ?: item.artworkUrl100,
                contentDescription = item.collectionName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.collectionName ?: "未命名專輯",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = item.artistName ?: "未知演出者",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val year = item.releaseDate?.take(4)
            val trackCount = item.trackCount
            val extraInfo = listOfNotNull(year, trackCount?.let { "${it}首曲目" }).joinToString(" • ")
            if (extraInfo.isNotEmpty()) {
                Text(
                    text = extraInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * 模組 4：雙按鈕確認彈窗 (寫回雲端 / 僅限本機顯示)
 */
@Composable
fun CoverConfirmDialog(
    item: ItunesAlbumItem,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onApply: (toCloud: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isApplying) onDismiss() },
        title = {
            Text(
                text = "更換專輯封面",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = item.highResArtworkUrl ?: item.artworkUrl100,
                    contentDescription = item.collectionName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = item.collectionName ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = item.artistName ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isApplying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在儲存並套用封面...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(
                        text = "請選擇封面的儲存與套用模式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 按鈕 1：【寫回雲端】
                    Button(
                        onClick = { onApply(true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("寫回雲端 (同步至 Nextcloud)", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 按鈕 2：【僅本機顯示】
                    FilledTonalButton(
                        onClick = { onApply(false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("僅本機顯示 (不更動雲端)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!isApplying) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

/**
 * 模組 4：自訂本機選取圖檔雙按鈕確認彈窗 (寫回雲端 / 僅限本機顯示)
 */
@Composable
fun LocalCoverConfirmDialog(
    imageBytes: ByteArray,
    albumName: String,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onApply: (toCloud: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isApplying) onDismiss() },
        title = {
            Text(
                text = "更換專輯封面",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = imageBytes,
                    contentDescription = albumName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = albumName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "已從本機相簿選取自訂圖片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isApplying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在儲存並套用封面...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(
                        text = "請選擇封面的儲存與套用模式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 按鈕 1：【寫回雲端】
                    Button(
                        onClick = { onApply(true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("寫回雲端 (同步至 Nextcloud)", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 按鈕 2：【僅本機顯示】
                    FilledTonalButton(
                        onClick = { onApply(false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("僅本機顯示 (不更動雲端)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!isApplying) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
