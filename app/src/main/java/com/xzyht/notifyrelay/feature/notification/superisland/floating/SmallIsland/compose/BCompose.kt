package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.rememberSuperIslandImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.*

/**
 * B区组件的Compose实现
 */
@Composable
fun BCompose(
    bComp: BComponent,
    picMap: Map<String, String>?
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (bComp) {
            is BImageText2 -> {
                // 图标
                if (!bComp.picKey.isNullOrBlank()) {
                    val iconUrl = picMap?.get(bComp.picKey) ?: ""
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BImageText3 -> {
                // 图标
                if (!bComp.picKey.isNullOrBlank()) {
                    val iconUrl = picMap?.get(bComp.picKey) ?: ""
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BImageText4 -> {
                // 图标 - 仅创建占位，不实际加载图片，与View版本保持一致
                if (!bComp.pic.isNullOrBlank()) {
                    // 仅创建占位，不实际加载图片
                    Spacer(modifier = Modifier.size(18.dp))
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = false,
                    highlight = false,
                    monospace = false
                )
            }
            is BImageText6 -> {
                // 图标 - 与View版本保持一致，只加载data URL格式的图片
                val iconUrl = picMap?.get(bComp.picKey)
                // 只处理data URL格式的图片，与View版本保持一致
                if (iconUrl?.startsWith("data:", ignoreCase = true) == true) {
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BTextInfo -> {
                // 文本内容
                BTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BFixedWidthDigitInfo -> {
                // 文本内容
                BTextBlockCompose(
                    frontTitle = null,
                    title = bComp.digit,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true
                )
            }
            
            is BSameWidthDigitInfo -> {
                // 处理计时信息或数字
                val timer = bComp.timer
                val initialTitleText = bComp.digit ?: timer?.let { formatTimerInfo(it) } ?: ""
                
                // 使用 mutableStateOf 存储标题文本
                var titleText by remember {
                    mutableStateOf(initialTitleText)
                }
                
                if (timer != null) {
                    LaunchedEffect(timer) {
                        // 每秒更新一次时间，与View版本保持一致
                        while (true) {
                            titleText = formatTimerInfo(timer)
                            kotlinx.coroutines.delay(1000L)
                        }
                    }
                } else if (bComp.digit != null) {
                    // 静态数字，直接赋值
                    titleText = bComp.digit
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = null,
                    title = titleText,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true
                )
            }
            
            is BProgressTextInfo -> {
                // 进度环 + 图标
                val size = 20.dp
                Box(
                    modifier = Modifier.size(size)
                ) {
                    // 实现圆形进度环
                    CircularProgressCompose(
                        progress = bComp.progress,
                        colorReach = Color(parseColorSafe(bComp.colorReach, 0xFF3482FF.toInt())),
                        colorUnReach = Color(parseColorSafe(bComp.colorUnReach, 0x33333333.toInt())),
                        strokeWidth = 2.5.dp,
                        isClockwise = !bComp.isCCW
                    )
                    
                    // 中心图标
                    bComp.picKey?.let { picKey ->
                        val iconUrl = picMap?.get(picKey) ?: ""
                        val painter = rememberSuperIslandImagePainter(iconUrl, picMap, picKey)
                        
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(size - 6.dp)
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
                
                // 文本内容
                BTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BPicInfo -> {
                // 图片组件
                val iconUrl = picMap?.get(bComp.picKey) ?: ""
                val painter = rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)
                
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            is BEmpty -> {
                // 空组件，不显示任何内容
            }
        }
    }
}

/**
 * B区文本块的Compose实现
 */
@Composable
fun BTextBlockCompose(
    frontTitle: String?,
    title: String?,
    content: String?,
    narrow: Boolean,
    highlight: Boolean,
    monospace: Boolean
) {
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .padding(start = 6.dp)
    ) {
        // 前置标题
        frontTitle?.let {
            Text(
                text = it,
                color = Color(0xCCFFFFFF),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = when {
                        monospace -> FontFamily.Monospace
                        narrow -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    }
                ),
                modifier = Modifier.widthIn(max = 120.dp)
            )
        }
        
        // 主标题
        title?.let {
            Text(
                text = it,
                color = if (highlight) Color(0xFF40C4FF) else Color.White,
                fontSize = 14.sp,
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
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
        
        // 内容
        content?.let {
            Text(
                text = it,
                color = Color(0xCCFFFFFF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = when {
                        monospace -> FontFamily.Monospace
                        narrow -> FontFamily.SansSerif
                        else -> FontFamily.Default
                    }
                ),
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}

/**
 * 安全解析颜色
 */
private fun parseColorSafe(s: String?, default: Int): Int = try {
    if (s.isNullOrBlank()) default else s.toIntOrNull(16)?.let { 0xFF000000.toInt() or it } ?: default
} catch (_: IllegalArgumentException) {
    default
}

/**
 * 圆形进度环的Compose实现
 */
@Composable
fun CircularProgressCompose(
    progress: Int,
    colorReach: Color,
    colorUnReach: Color,
    strokeWidth: Dp,
    isClockwise: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0, 100).toFloat() / 100f,
        animationSpec = tween(durationMillis = 420),
        label = "progress"
    )
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f - strokeWidth.toPx() / 2f
        
        // 绘制背景圆环
        drawCircle(
            color = colorUnReach,
            radius = radius,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth.toPx())
        )
        
        // 绘制进度圆环
        val startAngle = -90f
        val sweepAngle = if (isClockwise) animatedProgress * 360f else -animatedProgress * 360f
        drawArc(
            color = colorReach,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}

/**
 * 格式化计时信息
 */
private fun formatTimerInfo(timer: TimerInfo): String {
    val now = System.currentTimeMillis()
    val baseNow = timer.timerSystemCurrent ?: now
    val delta = now - baseNow
    val start = (timer.timerWhen ?: now) + delta
    val elapsed = (now - start).coerceAtLeast(0)
    val duration = if (timer.timerType == 1 && timer.timerTotal != null) {
        // type=1 为倒计时：剩余时间 = 总时长 - 已用时
        (timer.timerTotal - elapsed).coerceAtLeast(0)
    } else {
        // 其它视为正计时：显示已用时
        elapsed
    }
    return formatDuration(duration)
}

/**
 * 格式化时长
 */
private fun formatDuration(ms: Long): String {
    var totalSec = (ms / 1000).toInt()
    val hours = totalSec / 3600
    totalSec %= 3600
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}


