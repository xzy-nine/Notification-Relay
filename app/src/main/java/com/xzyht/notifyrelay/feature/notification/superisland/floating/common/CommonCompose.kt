package com.xzyht.notifyrelay.feature.notification.superisland.floating.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

/**
 * 通用图片加载组件
 * 用于A区和B区的图片渲染
 */
@Composable
fun CommonImageCompose(
    picKey: String?,
    picMap: Map<String, String>?,
    size: Dp = 18.dp,
    isFocusIcon: Boolean = false,
    contentDescription: String? = null
) {
    // 对于焦点图标，即使picKey为空，也尝试从picMap中获取
    if (!isFocusIcon && picKey.isNullOrBlank()) return
    
    // 获取当前主题
    val isDarkTheme = isSystemInDarkTheme()
    
    val iconUrl = if (isFocusIcon) {
        resolveFocusIconUrl(picMap, picKey, isDarkTheme)
    } else {
        resolveIconUrl(picMap, picKey)
    }
    
    val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(iconUrl, picMap)
    
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * 通用占位图片组件
 * 用于需要占位但不实际加载图片的场景
 */
@Composable
fun CommonImagePlaceholder(
    show: Boolean,
    size: Dp = 18.dp
) {
    if (show) {
        Spacer(modifier = Modifier.size(size))
    }
}

/**
 * 通用文本块的Compose实现
 * 用于A区和B区的文本渲染
 */
@Composable
fun CommonTextBlockCompose(
    frontTitle: String?,
    title: String?,
    content: String?,
    narrow: Boolean,
    highlight: Boolean,
    monospace: Boolean,
    horizontalPadding: Dp = 6.dp,
    frontTitleColor: Color = Color(0xCCFFFFFF),
    frontTitleFontSize: TextUnit = 11.sp,
    titleColor: Color = if (highlight) Color(0xFF40C4FF) else Color.White,
    titleFontSize: TextUnit = 14.sp,
    contentColor: Color = Color(0xCCFFFFFF),
    contentFontSize: TextUnit = 12.sp,
    maxWidth: Dp = 140.dp
) {
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .padding(start = horizontalPadding)
    ) {
        // 前置标题
        frontTitle?.let {
            Text(
                text = it,
                color = frontTitleColor,
                fontSize = frontTitleFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = when {
                        monospace -> FontFamily.Monospace
                        narrow -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    }
                ),
                modifier = Modifier.widthIn(max = maxWidth)
            )
        }
        
        // 主标题
        title?.let {
            Text(
                text = it,
                color = titleColor,
                fontSize = titleFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontFamily = when {
                        monospace -> FontFamily.Monospace
                        narrow -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    }
                ),
                modifier = Modifier.widthIn(max = maxWidth)
            )
        }
        
        // 内容
        content?.let {
            Text(
                text = it,
                color = contentColor,
                fontSize = contentFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = when {
                        monospace -> FontFamily.Monospace
                        narrow -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    }
                ),
                modifier = Modifier.widthIn(max = maxWidth)
            )
        }
    }
}

/**
 * 圆形进度环的Compose实现
 * 使用Miuix的CircularProgressIndicator实现
 */
@Composable
fun CircularProgressCompose(
    progress: Int,
    colorReach: Color,
    colorUnReach: Color,
    strokeWidth: Dp,
    isClockwise: Boolean,
    size: Dp = 20.dp
) {
    CircularProgressIndicator(
        progress = progress.coerceIn(0, 100).toFloat() / 100f,
        size = size,
        strokeWidth = strokeWidth,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = colorReach,
            backgroundColor = colorUnReach
        )
    )
}

/**
 * 安全解析颜色
 */
fun parseColorSafe(s: String?, default: Int): Int = try {
    if (s.isNullOrBlank()) default else s.toIntOrNull(16)?.let { 0xFF000000.toInt() or it } ?: default
} catch (_: IllegalArgumentException) {
    default
}

/**
 * 格式化BigIsland模型的计时信息
 */
fun formatTimerInfo(timer: com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.TimerInfo): String {
    val now = System.currentTimeMillis()
    val timerWhen = timer.timerWhen // 计时起点时间戳（毫秒）
    val timerSystemCurrent = timer.timerSystemCurrent // 服务器发送时的时间戳
    val timerTotal = timer.timerTotal // 计时总进度
    
    val displayValue: Long = when (timer.timerType) {
        -2 -> { // 倒计时暂停
            // 暂停状态：剩余时间 = 结束时间 - 发送时间，保持不变
            val remainingAtSend = timerWhen - timerSystemCurrent
            remainingAtSend.coerceAtLeast(0)
        }
        -1 -> { // 倒计时进行中
            // 进行中状态：剩余时间 = 结束时间 - 当前时间
            val remaining = timerWhen - now
            remaining.coerceAtLeast(0)
        }
        2 -> { // 正计时暂停
            // 暂停状态：已过时间 = 发送时间 - 开始时间，保持不变
            val elapsedAtSend = timerSystemCurrent - timerWhen
            elapsedAtSend.coerceAtLeast(0)
        }
        1 -> { // 正计时进行中
            // 进行中状态：已过时间 = 当前时间 - 开始时间
            val elapsed = now - timerWhen
            elapsed.coerceAtLeast(0)
        }
        else -> 0
    }
    
    val seconds = (displayValue / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format("%02d:%02d", minutes % 60, seconds % 60)
    }
}

/**
 * 格式化SmallIsland模型的计时信息
 */
fun formatTimerInfo(timer: com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.TimerInfo): String {
    val now = System.currentTimeMillis()
    val timerWhen = timer.timerWhen ?: now // 计时起点时间戳（毫秒）
    val timerSystemCurrent = timer.timerSystemCurrent ?: now // 服务器发送时的时间戳
    val timerTotal = timer.timerTotal ?: 0 // 计时总进度
    
    val displayValue: Long = when (timer.timerType) {
        -2 -> { // 倒计时暂停
            // 暂停状态：剩余时间 = 结束时间 - 发送时间，保持不变
            val remainingAtSend = timerWhen - timerSystemCurrent
            remainingAtSend.coerceAtLeast(0)
        }
        -1 -> { // 倒计时进行中
            // 进行中状态：剩余时间 = 结束时间 - 当前时间
            val remaining = timerWhen - now
            remaining.coerceAtLeast(0)
        }
        2 -> { // 正计时暂停
            // 暂停状态：已过时间 = 发送时间 - 开始时间，保持不变
            val elapsedAtSend = timerSystemCurrent - timerWhen
            elapsedAtSend.coerceAtLeast(0)
        }
        1 -> { // 正计时进行中
            // 进行中状态：已过时间 = 当前时间 - 开始时间
            val elapsed = now - timerWhen
            elapsed.coerceAtLeast(0)
        }
        else -> 0
    }
    
    val seconds = (displayValue / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format("%02d:%02d", minutes % 60, seconds % 60)
    }
}

/**
 * 格式化时长
 */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).toInt()
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    } else {
        String.format("%02d:%02d", minutes % 60, seconds % 60)
    }
}
