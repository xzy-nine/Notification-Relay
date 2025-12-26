package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CircularProgressCompose

/**
 * 圆形进度条Compose组件
 * 复用common目录下的通用组件
 */
@Composable
fun CircularProgressCompose(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 3.dp,
    progress: Int = 0,
    progressColor: Color = Color.Green,
    trackColor: Color = Color.Gray,
    clockwise: Boolean = true,
    animated: Boolean = true
) {
    Box(
        modifier = modifier.size(size)
    ) {
        CircularProgressCompose(
            progress = progress,
            colorReach = progressColor,
            colorUnReach = trackColor,
            strokeWidth = strokeWidth,
            isClockwise = clockwise
        )
    }
}
