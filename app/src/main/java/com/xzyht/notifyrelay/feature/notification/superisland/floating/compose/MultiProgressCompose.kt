package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.MultiProgressInfo
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF
private const val DEFAULT_NODE_COUNT = 3

/**
 * 多进度条Compose组件（使用 Miuix ProgressIndicator 恢复）
 */
@Composable
fun MultiProgressCompose(
    multiProgressInfo: MultiProgressInfo,
    picMap: Map<String, String>? = null,
    business: String? = null,
    modifier: Modifier = Modifier
) {
    val colorValue: Int = parseColor(multiProgressInfo.color) ?: DEFAULT_PRIMARY_COLOR.toInt()
    val primaryColor = Color(colorValue)
    val requestedPoints = if (multiProgressInfo.points == null) DEFAULT_NODE_COUNT else multiProgressInfo.points.toInt()
    val nodeCount: Int = if (requestedPoints > 2) requestedPoints else 2

    val progressInt: Int = (multiProgressInfo.progress).toInt()
    val progressFloat: Float? = if (progressInt in 0..100) progressInt / 100f else null

    // 计算指针索引（用于高亮已完成节点）
    val pointerIndex = progressFloat?.let { (it * (nodeCount - 1)).toInt().coerceIn(0, nodeCount - 1) } ?: -1

    Column(modifier = modifier.padding(8.dp)) {
        // 标题
        if (!multiProgressInfo.title.isNullOrBlank()) {
            androidx.compose.material3.Text(
                text = multiProgressInfo.title,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 节点行（等距）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(nodeCount) { index ->
                val completed = if (pointerIndex >= 0) index <= pointerIndex else false
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (completed) primaryColor else primaryColor.copy(alpha = 0.28f),
                            shape = CircleShape
                        )
                )
            }
        }

        // 线性进度条（确定/不确定两种状态，progress=null 表示不确定）
        LinearProgressIndicator(
            progress = progressFloat,
            modifier = Modifier
                .fillMaxWidth()
                .height(ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = primaryColor,
                backgroundColor = primaryColor.copy(alpha = 0.2f)
            )
        )

        // 可选额外信息：显示数值
        progressFloat?.let { pf ->
            androidx.compose.material3.Text(
                text = "${(pf * 100).toInt()}%",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
