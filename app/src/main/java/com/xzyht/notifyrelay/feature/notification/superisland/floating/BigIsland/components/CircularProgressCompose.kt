package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆形进度条Compose组件
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
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    
    // 使用Animatable处理进度动画
    val animatedProgress = remember {
        Animatable(0f)
    }
    
    // 当progress变化时，启动动画
    LaunchedEffect(progress, animated) {
        if (animated) {
            animatedProgress.animateTo(
                targetValue = progress.toFloat(),
                animationSpec = tween(
                    durationMillis = 420,
                    easing = LinearEasing
                )
            )
        } else {
            animatedProgress.snapTo(progress.toFloat())
        }
    }
    
    Canvas(modifier = modifier.size(size)) {
        val centerX = size.toPx() / 2
        val centerY = size.toPx() / 2
        val radius = (size.toPx() - strokeWidthPx) / 2
        
        // 绘制背景圆环
        drawCircle(
            color = trackColor,
            radius = radius,
            style = Stroke(width = strokeWidthPx)
        )
        
        // 计算进度圆弧的角度
        val startAngle = -90f
        val sweepAngle = (animatedProgress.value / 100f) * 360f
        val actualSweepAngle = if (clockwise) sweepAngle else -sweepAngle
        
        // 绘制进度圆弧
        drawArc(
            color = progressColor,
            startAngle = startAngle,
            sweepAngle = actualSweepAngle,
            useCenter = false,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )
    }
}
