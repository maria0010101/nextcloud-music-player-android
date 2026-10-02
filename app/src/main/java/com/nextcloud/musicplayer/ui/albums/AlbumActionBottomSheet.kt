package com.nextcloud.musicplayer.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextcloud.musicplayer.data.local.entity.AlbumEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumActionBottomSheet(
    album: AlbumEntity,
    onDismiss: () -> Unit,
    onAppendToQueue: () -> Unit,
    onClearAndPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onViewAlbumDetail: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Album Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = album.coverUrl,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "共 ${album.trackCount} 首曲目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

            // Action 1: 加入播放序列 (需求 3)
            ActionMenuItem(
                icon = Icons.Default.PlaylistAdd,
                title = "加入播放序列",
                subtitle = "將此專輯所有歌曲加入目前播放佇列後方",
                onClick = {
                    onAppendToQueue()
                    onDismiss()
                }
            )

            // Action 2: 清除播放序列並播放 (需求 4)
            ActionMenuItem(
                icon = Icons.Default.PlayCircle,
                title = "清除播放序列並播放",
                subtitle = "清空目前佇列並立即起播此專輯",
                onClick = {
                    onClearAndPlay()
                    onDismiss()
                }
            )

            // Action 3: 加入自訂播放清單 (需求 2)
            ActionMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "加入自訂播放清單",
                subtitle = "將專輯曲目儲存至自選播放列表",
                onClick = {
                    onDismiss()
                    onAddToPlaylist()
                }
            )

            // Action 4: 我的最愛切換
            ActionMenuItem(
                icon = if (album.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = if (album.isFavorite) "取消收藏專輯" else "加入最愛專輯",
                subtitle = if (album.isFavorite) "從最愛清單移除" else "收藏至最愛專輯庫",
                tint = if (album.isFavorite) MaterialTheme.colorScheme.error else null,
                onClick = {
                    onToggleFavorite()
                    onDismiss()
                }
            )

            // Action 5: 查看專輯曲目詳情
            ActionMenuItem(
                icon = Icons.Default.Album,
                title = "查看專輯曲目詳情",
                subtitle = "瀏覽該專輯內所有單曲與詳細中繼資料",
                onClick = {
                    onDismiss()
                    onViewAlbumDetail()
                }
            )
        }
    }
}

@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
