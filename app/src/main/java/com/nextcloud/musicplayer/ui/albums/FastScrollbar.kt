package com.nextcloud.musicplayer.ui.albums

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 需求 1：專輯列表快速捲動軸（Fast Scrollbar）
 * 1. 支援手勢按住上下拖曳（Draggable / Fast Scroll），即時平滑/跳轉至對應位置。
 * 2. 拖曳時於滑塊旁顯示對應首字字母或名稱之提示氣泡（Indicator Bubble）。
 * 3. 未觸碰或靜止時自動半透明/隱藏，滑動時顯現。
 */
@Composable
fun FastScrollbar(
    totalItemCount: Int,
    firstVisibleIndex: Int,
    isScrollInProgress: Boolean,
    onScrollToItem: (Int) -> Unit,
    getIndicatorText: (Int) -> String,
    modifier: Modifier = Modifier
) {
    if (totalItemCount <= 0) return

    val density = LocalDensity.current
    val thumbHeightDp = 52.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val bubbleSizeDp = 48.dp
    val bubbleSizePx = with(density) { bubbleSizeDp.toPx() }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // 捲動/觸碰狀態自動顯隱 (靜止 1.5 秒後淡出)
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isDragging, isScrollInProgress) {
        if (isDragging || isScrollInProgress) {
            isVisible = true
        } else {
            delay(1500)
            isVisible = false
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.15f,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbarAlpha"
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbWidth"
    )

    // 當前有效捲動進度 (0f..1f)
    val currentProgress = if (isDragging) {
        dragProgress
    } else {
        if (totalItemCount > 1) {
            (firstVisibleIndex.toFloat() / (totalItemCount - 1)).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    val maxScrollPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbY = currentProgress * maxScrollPx

    val currentIndex = (currentProgress * (totalItemCount - 1)).roundToInt().coerceIn(0, totalItemCount - 1)
    val indicatorText = remember(currentIndex, totalItemCount) {
        getIndicatorText(currentIndex)
    }

    Box(
        modifier = modifier
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .graphicsLayer { this.alpha = alpha }
            .pointerInput(totalItemCount, trackHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    val maxScroll = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
                    var currentY = (down.position.y - thumbHeightPx / 2f).coerceIn(0f, maxScroll)
                    dragProgress = currentY / maxScroll
                    val targetIndex = (dragProgress * (totalItemCount - 1)).roundToInt().coerceIn(0, totalItemCount - 1)
                    onScrollToItem(targetIndex)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        currentY = (change.position.y - thumbHeightPx / 2f).coerceIn(0f, maxScroll)
                        dragProgress = currentY / maxScroll
                        val newTarget = (dragProgress * (totalItemCount - 1)).roundToInt().coerceIn(0, totalItemCount - 1)
                        onScrollToItem(newTarget)
                    }
                    isDragging = false
                }
            }
            .width(36.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopEnd
    ) {
        // 1. 軌道槽線 (Track)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(2.dp)
                )
        )

        // 2. 拖曳滑塊 (Thumb)
        Box(
            modifier = Modifier
                .padding(end = 2.dp)
                .offset { IntOffset(0, thumbY.roundToInt()) }
                .width(thumbWidth)
                .height(thumbHeightDp)
                .background(
                    color = if (isDragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    },
                    shape = RoundedCornerShape(4.dp)
                )
        )

        // 3. 首字氣泡指示器 (Bubble Indicator)
        AnimatedVisibility(
            visible = isDragging && indicatorText.isNotBlank(),
            enter = fadeIn(tween(100)) + scaleIn(tween(100)),
            exit = fadeOut(tween(150)) + scaleOut(tween(150)),
            modifier = Modifier
                .padding(end = 44.dp)
                .offset {
                    val bubbleY = (thumbY + (thumbHeightPx - bubbleSizePx) / 2f)
                        .coerceIn(0f, (trackHeightPx - bubbleSizePx).coerceAtLeast(0f))
                    IntOffset(0, bubbleY.roundToInt())
                }
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier.size(bubbleSizeDp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = indicatorText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
