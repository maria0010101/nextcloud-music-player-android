package com.nextcloud.musicplayer.ui.common

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 依據設定條件動態套用跑馬燈效果 (Conditional Marquee)
 *
 * @param enabled 是否啟用跑馬燈滾動
 * @param iterations 滾動次數，預設 Int.MAX_VALUE 無限循環
 * @param initialDelayMillis 初始進入或文字回正時的停留毫秒數，預設 2000ms
 * @param repeatDelayMillis 每次輪播循環間的停留毫秒數，預設 2000ms
 * @param velocity 橫向滾動速率，預設 25.dp
 */
fun Modifier.conditionalMarquee(
    enabled: Boolean,
    iterations: Int = Int.MAX_VALUE,
    initialDelayMillis: Int = 2000,
    repeatDelayMillis: Int = 2000,
    velocity: Dp = 25.dp
): Modifier = if (enabled) {
    this.basicMarquee(
        iterations = iterations,
        initialDelayMillis = initialDelayMillis,
        repeatDelayMillis = repeatDelayMillis,
        velocity = velocity
    )
} else {
    this
}
