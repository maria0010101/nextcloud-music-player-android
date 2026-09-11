package com.nextcloud.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nextcloud.musicplayer.audio.AudioEffectManager
import com.nextcloud.musicplayer.audio.HeadphoneProfile
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundEffectsBottomSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val audioEffectManager = playerViewModel.audioEffectManager
    val soundProfileManager = playerViewModel.soundProfileManager

    val eqEnabled by audioEffectManager.eqEnabled.collectAsState()
    val eqGains by audioEffectManager.eqGains.collectAsState()
    val activePreset by audioEffectManager.activePreset.collectAsState()

    val profileEnabled by audioEffectManager.profileEnabled.collectAsState()
    val activeProfile by audioEffectManager.activeProfile.collectAsState()

    val channelBalance by audioEffectManager.channelBalance.collectAsState()
    val volumeSteps by playerViewModel.volumeSteps.collectAsState()

    var showHeadphoneSearchDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 標題列
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "音訊與等化器設定",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "關閉")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ──────────────────────────────────────────────
            // 1. 耳機音質校準設定檔 (AutoEq 6000+ 模型)
            // ──────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Headphones,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "耳機音質校準 (AutoEq)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Switch(
                            checked = profileEnabled,
                            onCheckedChange = { audioEffectManager.setProfileEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (activeProfile != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeProfile!!.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "類別: ${activeProfile!!.category} • 前級增益: ${activeProfile!!.preamp} dB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row {
                                    IconButton(onClick = { showHeadphoneSearchDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "更換耳機")
                                    }
                                    IconButton(onClick = { audioEffectManager.selectHeadphoneProfile(null) }) {
                                        Icon(Icons.Default.Clear, contentDescription = "清除")
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showHeadphoneSearchDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("搜尋與選取耳機型號 (收錄 6000+ 款)")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ──────────────────────────────────────────────
            // 2. 10 頻段圖形等化器 (10-Band Graphic Equalizer)
            // ──────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "10 頻段圖形等化器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = { audioEffectManager.setEqEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 預設風格選擇列 (Presets)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioEffectManager.PRESETS.keys.forEach { presetName ->
                            val isSelected = activePreset == presetName
                            FilterChip(
                                selected = isSelected,
                                onClick = { audioEffectManager.applyPreset(presetName) },
                                label = { Text(presetName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 10 頻段調節滑桿
                    AudioEffectManager.BANDS_10.forEachIndexed { index, freq ->
                        val gain = if (index < eqGains.size) eqGains[index] else 0f
                        val label = AudioEffectManager.BAND_LABELS[index]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(44.dp)
                            )

                            Slider(
                                value = gain,
                                onValueChange = { newGain ->
                                    audioEffectManager.setBandGain(index, (newGain * 2).roundToInt() / 2f)
                                },
                                valueRange = -12.0f..12.0f,
                                steps = 47,
                                modifier = Modifier.weight(1f),
                                enabled = eqEnabled
                            )

                            Text(
                                text = String.format(Locale.US, "%+.1f dB", gain),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (gain != 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (gain != 0f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.width(60.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { audioEffectManager.resetEq() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("重設等化器至原音 (Flat)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ──────────────────────────────────────────────
            // 3. 聲道平衡 (Channel Balance)
            // ──────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "立體聲聲道平衡",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = when {
                                channelBalance < -0.05f -> "左偏 ${(-channelBalance * 100).roundToInt()}%"
                                channelBalance > 0.05f -> "右偏 ${(channelBalance * 100).roundToInt()}%"
                                else -> "置中 (平衡)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("L", fontWeight = FontWeight.Bold)
                        Slider(
                            value = channelBalance,
                            onValueChange = { audioEffectManager.setChannelBalance(it) },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text("R", fontWeight = FontWeight.Bold)
                    }

                    if (channelBalance != 0.0f) {
                        TextButton(
                            onClick = { audioEffectManager.setChannelBalance(0.0f) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("重設置中")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // ──────────────────────────────────────────────
    // 耳機型號搜尋彈出視窗
    // ──────────────────────────────────────────────
    if (showHeadphoneSearchDialog) {
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf(soundProfileManager.loadProfiles().take(50)) }

        LaunchedEffect(searchQuery) {
            searchResults = soundProfileManager.searchProfiles(searchQuery)
        }

        Dialog(onDismissRequest = { showHeadphoneSearchDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 550.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "選取耳機音質校準檔",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜尋耳機型號 (如: Sony, AirPods, HD 600)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(searchResults, key = { it.name }) { profile ->
                            ListItem(
                                headlineContent = { Text(profile.name, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text("類別: ${profile.category} • 前級: ${profile.preamp} dB") },
                                leadingContent = {
                                    Icon(Icons.Default.Headphones, contentDescription = null)
                                },
                                modifier = Modifier
                                    .clickable {
                                        audioEffectManager.selectHeadphoneProfile(profile)
                                        showHeadphoneSearchDialog = false
                                    }
                            )
                            HorizontalDivider()
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showHeadphoneSearchDialog = false }) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
}
