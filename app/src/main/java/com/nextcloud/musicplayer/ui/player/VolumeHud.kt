package com.nextcloud.musicplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun VolumeHud(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val showHud by playerViewModel.showVolumeHud.collectAsState()
    val volumeSteps by playerViewModel.volumeSteps.collectAsState()
    val currentStep by playerViewModel.currentStep.collectAsState()

    // 僅在 25 或 50 段自訂精度模式下啟用浮動音量條
    if (volumeSteps != 25 && volumeSteps != 50) return

    val targetFraction = if (volumeSteps > 0) {
        (currentStep.toFloat() / volumeSteps.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 80),
        label = "volumeFraction"
    )

    AnimatedVisibility(
        visible = showHud,
        enter = fadeIn(animationSpec = tween(150)) + slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(200)
        ),
        exit = fadeOut(animationSpec = tween(250)) + slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(250)
        ),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 16.dp)
        ) {
            // 級距數值徽章 (例如: 36 / 50)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = null
            ) {
                Text(
                    text = "$currentStep / $volumeSteps",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 仿 Android 原生垂直膠囊音量條
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(200.dp)
                    .shadow(12.dp, RoundedCornerShape(25.dp))
                    .clip(RoundedCornerShape(25.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(volumeSteps) {
                        detectTapGestures { offset ->
                            val fraction = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                            playerViewModel.setVolumeFraction(fraction)
                        }
                    }
                    .pointerInput(volumeSteps) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val fraction = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                                playerViewModel.setVolumeFraction(fraction)
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val fraction = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                                playerViewModel.setVolumeFraction(fraction)
                            },
                            onDragEnd = {
                                playerViewModel.triggerVolumeHud()
                            },
                            onDragCancel = {
                                playerViewModel.triggerVolumeHud()
                            }
                        )
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                // 音量填充條 (自底向上填充)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFraction)
                        .background(MaterialTheme.colorScheme.primary)
                )

                // 底部音量狀態圖示
                val icon = when {
                    currentStep == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                    animatedFraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }

                val iconTint = if (animatedFraction > 0.18f) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .padding(bottom = 14.dp)
                        .size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "音量",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
