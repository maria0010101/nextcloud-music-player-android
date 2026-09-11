package com.nextcloud.musicplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcloud.musicplayer.ui.folder.FolderPickerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSoundEffects: () -> Unit = {},
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val musicFolder by viewModel.musicFolder.collectAsState()
    val albumNameLevels by viewModel.albumNameLevels.collectAsState()
    val cacheMaxBytes by viewModel.cacheMaxSizeBytes.collectAsState()
    val usedCacheBytes by viewModel.usedCacheBytes.collectAsState()
    val downloadStorageUri by viewModel.downloadStorageUri.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    val volumeSteps by viewModel.volumeSteps.collectAsState()

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateDownloadStorageUri(context, uri)
        }
    }

    var showFolderPicker by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialFolder = musicFolder,
            repository = viewModel.repository,
            onFolderSelected = { chosen ->
                viewModel.updateMusicFolder(chosen)
            },
            onDismiss = { showFolderPicker = false }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("登出帳號") },
            text = { Text("確定要登出並清除本機 Nextcloud 連線設定與快取嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout(onLogout)
                }) {
                    Text("確定登出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                windowInsets = WindowInsets(0.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 1. 音樂庫路徑設定
            Text(
                text = "音樂庫目錄設定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("指定掃描資料夾", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    val displayFolder = if (musicFolder.isEmpty()) "根目錄 (/)" else "/$musicFolder"
                    Text(
                        text = displayFolder,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showFolderPicker = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("瀏覽選取")
                        }

                        Button(
                            onClick = { viewModel.quickSync() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("同步中...")
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("快速同步")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { viewModel.fullRescan() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isSyncing,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("強制完整重新掃描 (Full Rescan)")
                    }

                    Text(
                        text = "快速同步僅比對新增/異動資料夾並移除失效專輯；若中繼資料異常可使用強制重新掃描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (syncMessage.isNotEmpty()) {
                            Text(
                                text = syncMessage,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 2. 專輯名稱顯示階層 (自訂資料夾階層 1..5)
            Text(
                text = "專輯名稱顯示階層",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("勾選欲包含於專輯名稱中的資料夾階層：", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val levelOptions = listOf(
                        1 to "階層 1（掃描根目錄）",
                        2 to "階層 2（第一層子資料夾）",
                        3 to "階層 3（第二層子資料夾）",
                        4 to "階層 4（第三層子資料夾）",
                        5 to "階層 5（第四層子資料夾）"
                    )

                    levelOptions.forEach { (level, label) ->
                        val isChecked = albumNameLevels.contains(level)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newSet = if (isChecked) albumNameLevels - level else albumNameLevels + level
                                    viewModel.updateAlbumNameLevels(newSet)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val newSet = if (checked) albumNameLevels + level else albumNameLevels - level
                                    viewModel.updateAlbumNameLevels(newSet)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "範例預覽：${viewModel.previewAlbumName(albumNameLevels)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "路徑以 /Music/Rock/Classic/Pink Floyd/The Wall 為例",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 3. 緩衝快取上限設定 (模組 4 & 5)
            Text(
                text = "音訊串流快取管理",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快取上限限制 (128MB ~ 2GB)", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    val maxMb = cacheMaxBytes / (1024 * 1024)
                    val usedMb = usedCacheBytes / (1024f * 1024f)
                    Text(
                        text = "目前已使用: %.1f MB / 上限: %d MB".format(usedMb, maxMb),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 常用級距選擇按鈕群
                    val cachePresets = listOf(
                        128L * 1024 * 1024 to "128MB",
                        256L * 1024 * 1024 to "256MB",
                        512L * 1024 * 1024 to "512MB",
                        1024L * 1024 * 1024 to "1GB",
                        2048L * 1024 * 1024 to "2GB"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        cachePresets.forEach { (presetBytes, label) ->
                            val isSelected = cacheMaxBytes == presetBytes
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.updateCacheMaxSize(presetBytes) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { viewModel.clearCache() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("清理全部串流暫存快取")
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 4. 離線下載儲存設定 (模組 1 & 2)
            Text(
                text = "離線下載儲存設定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("下載檔案存放目錄", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    val displayLocation = viewModel.formatDownloadStorageLocation(context, downloadStorageUri)
                    Text(
                        text = displayLocation,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (downloadStorageUri.isNullOrBlank()) {
                            "目前使用 App 專屬外部空間，解除安裝時檔案會被清除。可自訂至手機公開 Music 資料夾或 SD 卡。"
                        } else {
                            "已設定自訂 SAF 目錄並取得持久化讀寫授權，App 解除安裝後檔案仍會永久保留於裝置中。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { openDocumentTreeLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("更改目錄")
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetDownloadStorageUri(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !downloadStorageUri.isNullOrBlank()
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("恢復預設")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 5. 音效與等化器 (AutoEq 耳機校準、10 頻段等化器、聲道平衡)
            Text(
                text = "音訊效果與等化器",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AutoEq 耳機音質校準、10 頻段圖形等化器與立體聲聲道平衡",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onOpenSoundEffects,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("開啟音訊等化器與耳機音質調整")
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 6. 音量調節精度 (32steps 架構)
            Text(
                text = "音量調節精度",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("自訂音量段數（採用 32steps 微步階 DSP 衰減演算法，切換平滑無爆音）", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val volumeOptions = listOf(
                        15 to "預設（依循系統 15 段）",
                        25 to "高精度 25 段",
                        30 to "進階 30 段",
                        50 to "超高精度 50 段",
                        100 to "極致精準 100 段"
                    )

                    volumeOptions.forEach { (steps, label) ->
                        val isSelected = volumeSteps == steps
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updateVolumeSteps(steps) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.updateVolumeSteps(steps) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 6. 帳號與連線資訊
            Text(
                text = "Nextcloud 伺服器資訊",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("伺服器端點", style = MaterialTheme.typography.labelMedium)
                    Text(viewModel.serverUrl, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(if (viewModel.isPublicShare) "連線模式" else "登入帳號", style = MaterialTheme.typography.labelMedium)
                    val accountText = if (viewModel.isPublicShare) "公開分享連結 (Token: ${viewModel.loginName})" else viewModel.loginName
                    Text(accountText, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { showLogoutDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (viewModel.isPublicShare) "清除公開分享連線" else "登出 Nextcloud 帳號")
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
