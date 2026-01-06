package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.MediaSessionData
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COVER_SIZE = 80
/**
 * 自动文本缩放换行组件
 * 当文本长度超过容器宽度时，自动实现缩放换行效果
 *
 * @param text 要显示的文本内容
 * @param style 文本样式
 * @param color 文本颜色
 * @param minTextSize 最小字号
 */
@Composable
private fun AutoFitText(
    text: String,
    style: TextStyle,
    color: Color,
    minTextSize: Float = 10f // sp
) {
    val initialSize = if (style.fontSize.isUnspecified) 14.sp else style.fontSize
    var currentSize by remember { mutableStateOf(initialSize) }
    var enableWrap by remember { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        style = style.copy(fontSize = currentSize),
        maxLines = if (enableWrap) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout: TextLayoutResult ->
            if (!enableWrap && layout.hasVisualOverflow) {
                val newSizeSp = (currentSize.value * 0.92f)
                if (newSizeSp <= minTextSize) {
                    enableWrap = true
                } else {
                    currentSize = newSizeSp.sp
                }
            }
        }
    )
}

/**
 * 自动滚动文本组件
 * 当文本长度超过容器宽度时，自动实现滚动效果
 *
 * @param text 要显示的文本内容
 * @param style 文本样式
 * @param color 文本颜色
 * @param baseSpeedPxPerSec 基础滚动速度，单位为像素/秒，默认100f
 * @param pauseMillis 滚动开始前和滚动结束后的暂停时间，单位为毫秒，默认0
 */
@Composable
private fun AutoScrollText(
    text: String,
    style: TextStyle,
    color: Color,
    baseSpeedPxPerSec: Float = 100f,
    pauseMillis: Int = 0
) {
    // 容器宽度，用于判断是否需要滚动
    var containerWidth by remember { mutableStateOf(0) }
    // 文本滚动偏移量，用于控制文本位置
    val offset = remember { Animatable(0f) }
    // 文本测量器，用于测量文本宽度
    val textMeasurer = rememberTextMeasurer()

    // 测量文本的固有宽度（像素）
    val measured = textMeasurer.measure(AnnotatedString(text), style = style, maxLines = 1, softWrap = false)
    val intrinsicWidth = measured.size.width

    // 判断是否需要滚动：容器宽度大于0且文本宽度超过容器宽度
    val canScroll = containerWidth > 0 && intrinsicWidth > containerWidth
    // 滚动范围：文本宽度减去容器宽度，确保不小于0
    val scrollRange = (intrinsicWidth - containerWidth).coerceAtLeast(0)
    // 实际滚动速度：基础速度加上根据文本长度的调整值
    val speed = baseSpeedPxPerSec + (intrinsicWidth / 10f)
    // 滚动动画持续时间：根据滚动范围和速度计算，最小400毫秒
    val duration = if (canScroll) {
        (((scrollRange + containerWidth) / speed) * 1000).toInt().coerceAtLeast(400)
    } else 0

    // 将像素转换为dp单位
    val intrinsicWidthDp = with(LocalDensity.current) { intrinsicWidth.toDp() }

    // 容器组件，用于限制文本显示范围
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 获取容器实际宽度
            .onGloballyPositioned { coordinates ->
                containerWidth = coordinates.size.width
            }
            // 裁剪超出容器的内容，解决文本滚动时的裁剪问题
            .clipToBounds()
    ) {
        // 滚动文本组件
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            // 允许文本在容器外绘制，配合外层clipToBounds实现平滑滚动
            overflow = TextOverflow.Visible,
            modifier = Modifier
                // 设置文本宽度为实际测量宽度
                .width(intrinsicWidthDp)
                // 通过translationX控制文本滚动位置
                .graphicsLayer {
                    translationX = offset.value
                }
        )
    }

    // 滚动动画逻辑
    LaunchedEffect(canScroll, intrinsicWidth, containerWidth, duration) {
        // 重置偏移量到初始位置
        offset.snapTo(0f)
        // 不需要滚动时直接返回
        if (!canScroll) return@LaunchedEffect
        
        // 无限循环滚动
        while (true) {
            // 滚动开始前暂停
            delay(pauseMillis.toLong())
            // 执行滚动动画
            offset.animateTo(
                -scrollRange.toFloat(), // 滚动到终点位置
                animationSpec = tween(durationMillis = duration, easing = LinearEasing) // 线性动画
            )
            // 重置到初始位置
            offset.snapTo(0f)
            // 滚动结束后短暂暂停
            delay(300)
        }
    }
}

@Composable
fun MediaIslandCompose(
    mediaSession: MediaSessionData,
    isExpanded: Boolean = true,
    onCollapse: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    ExpandedMediaIsland(
        mediaSession = mediaSession,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onClose = onClose
    )
}

@Composable
private fun ExpandedMediaIsland(
    mediaSession: MediaSessionData,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(
                url = mediaSession.coverUrl
            )
            
            // 只有在有封面时才显示封面图片
            if (painter != null) {
                Box(
                    modifier = Modifier
                        .size(COVER_SIZE.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = "歌曲封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                // 只有在有封面时才添加间距
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                AutoScrollText(
                    text = mediaSession.title.ifBlank { "未知标题" },
                    style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFFFFFFFF.toInt()),
                    baseSpeedPxPerSec = 80f,
                    pauseMillis = 800
                )

                Spacer(modifier = Modifier.height(4.dp))

                AutoFitText(
                    text = mediaSession.text.ifBlank { "未知艺术家" },
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFDDDDDD.toInt()),
                    minTextSize = 10f
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "来自: ${mediaSession.deviceName}",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFDDDDDD.toInt()),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clickable { onPrevious() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "上一首",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }

            Box(
                modifier = Modifier
                    .clickable { onPlayPause() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "播放/暂停",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }

            Box(
                modifier = Modifier
                    .clickable { onNext() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "下一首",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }
        }
    }
}
