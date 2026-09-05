package com.nextcloud.musicplayer.ui.settings

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
import androidx.compose.ui.unit.dp
import com.nextcloud.musicplayer.ui.folder.FolderPickerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val musicFolder by viewModel.musicFolder.collectAsState()
    val cacheMaxBytes by viewModel.cacheMaxSizeBytes.collectAsState()
    val usedCacheBytes by viewModel.usedCacheBytes.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

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
        topBar = {
            TopAppBar(
                title = { Text("偏好設定") },
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
                        Button(
                            onClick = { showFolderPicker = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("瀏覽選取")
                        }

                        OutlinedButton(
                            onClick = { viewModel.rescanLibrary() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("掃描中...")
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即掃描")
                            }
                        }
                    }

                    if (syncMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = syncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 2. 緩衝快取上限設定 (模組 4 & 5)
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

            // 3. 帳號與連線資訊
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

                    Text("登入帳號", style = MaterialTheme.typography.labelMedium)
                    Text(viewModel.loginName, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { showLogoutDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("登出 Nextcloud 帳號")
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
