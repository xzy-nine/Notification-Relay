package com.xzyht.notifyrelay.feature.notification.superisland.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 自动适应文本组件
 * 当文本过长时，自动缩小字体大小，直到达到最小字体大小后换行
 *
 * @param text 要显示的文本内容
 * @param style 文本样式
 * @param color 文本颜色
 * @param minTextSize 最小字体大小，单位为sp，默认10f
 */
@Composable
fun AutoFitText(
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
 * 支持自适应容器边界：
 * - 当文本长度未超过容器宽度时，文本不滚动，自适应撑开容器
 * - 当文本长度超过容器宽度时，自动实现滚动效果
 *
 * @param text 要显示的文本内容
 * @param style 文本样式
 * @param color 文本颜色
 * @param baseSpeedPxPerSec 基础滚动速度，单位为像素/秒，默认100f
 * @param pauseMillis 滚动开始前和滚动结束后的暂停时间，单位为毫秒，默认0
 * @param maxWidth 最大宽度限制，超过此宽度才会滚动，默认使用父容器宽度
 */
@Composable
fun AutoScrollText(
    text: String,
    style: TextStyle,
    color: Color,
    baseSpeedPxPerSec: Float = 100f,
    pauseMillis: Int = 0,
    maxWidth: Int? = null
) {
    // 父容器可用宽度
    var parentWidth by remember { mutableStateOf(0) }
    // 文本滚动偏移量，用于控制文本位置
    val offset = remember { Animatable(0f) }
    // 文本测量器，用于测量文本宽度
    val textMeasurer = rememberTextMeasurer()

    // 测量文本的固有宽度（像素）
    val measured = textMeasurer.measure(AnnotatedString(text), style = style, maxLines = 1, softWrap = false)
    val intrinsicWidth = measured.size.width

    // 实际最大宽度限制
    val actualMaxWidth = maxWidth ?: parentWidth
    // 判断是否需要滚动：
    // 1. 有实际最大宽度限制
    // 2. 文本宽度超过最大宽度限制
    val canScroll = actualMaxWidth > 0 && intrinsicWidth > actualMaxWidth
    // 滚动范围：文本宽度减去最大宽度限制，确保不小于0
    val scrollRange = (intrinsicWidth - actualMaxWidth).coerceAtLeast(0)
    // 实际滚动速度：基础速度加上根据文本长度的调整值
    val speed = baseSpeedPxPerSec + (intrinsicWidth / 10f)
    // 滚动动画持续时间：根据滚动范围和速度计算，最小400毫秒
    val duration = if (canScroll) {
        (((scrollRange + actualMaxWidth) / speed) * 1000).toInt().coerceAtLeast(400)
    } else 0

    // 将像素转换为dp单位
    val intrinsicWidthDp = with(LocalDensity.current) { intrinsicWidth.toDp() }
    val actualMaxWidthDp = with(LocalDensity.current) { actualMaxWidth.toDp() }

    // 容器组件：
    // - 不强制占满父容器，根据内容自适应
    // - 只有在需要滚动时才设置最大宽度限制
    Box(
        modifier = if (canScroll) {
            Modifier
                // 监听父容器宽度变化
                .onGloballyPositioned { coordinates ->
                    if (maxWidth == null) {
                        parentWidth = coordinates.size.width
                    }
                }
                .clipToBounds()
        } else {
            Modifier
                // 监听父容器宽度变化
                .onGloballyPositioned { coordinates ->
                    if (maxWidth == null) {
                        parentWidth = coordinates.size.width
                    }
                }
        }
    ) {
        val textModifier = if (canScroll) {
            Modifier
                .width(intrinsicWidthDp)
                .graphicsLayer {
                    translationX = offset.value
                }
        } else {
            Modifier
        }
        
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            // 只有在不需要滚动时才显示省略号，滚动时显示完整文本
            overflow = if (canScroll) TextOverflow.Visible else TextOverflow.Ellipsis,
            modifier = textModifier
        )
    }

    // 滚动动画逻辑
    LaunchedEffect(canScroll, intrinsicWidth, actualMaxWidth, duration) {
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