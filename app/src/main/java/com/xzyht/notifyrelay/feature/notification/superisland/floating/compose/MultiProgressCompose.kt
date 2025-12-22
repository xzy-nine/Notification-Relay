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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.MultiProgressInfo

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF
private const val DEFAULT_NODE_COUNT = 3
private const val NODE_SIZE_DP = 55 // 与传统View保持一致
private const val PROGRESS_BAR_HEIGHT_DP = 8 // 与传统View保持一致

/**
 * 多进度条Compose组件，与传统View实现功能一致
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
    val progressValue = progressInt.coerceIn(0, 100)
    val segmentCount = nodeCount - 1
    val stageFloat = progressValue / 100f * segmentCount
    val pointerIndex = stageFloat.toInt().coerceIn(0, nodeCount - 1)

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

        Box(modifier = Modifier.fillMaxWidth()) {
            // 进度条背景层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PROGRESS_BAR_HEIGHT_DP.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        color = primaryColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(PROGRESS_BAR_HEIGHT_DP.dp / 2)
                    )
            )

            // 进度条前景层
            if (progressFloat != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressFloat)
                        .height(PROGRESS_BAR_HEIGHT_DP.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            color = primaryColor,
                            shape = RoundedCornerShape(PROGRESS_BAR_HEIGHT_DP.dp / 2)
                        )
                )
            }

            // 节点行（等距，底部对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NODE_SIZE_DP.dp) // 确保有足够高度容纳节点
                    .padding(bottom = 0.dp), // 与进度条底部对齐
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom // 节点底部对齐
            ) {
                repeat(nodeCount) {
                    index ->
                    val completed = index <= pointerIndex
                    val isFirst = index == 0
                    // 针对 food_delivery 业务，第一个节点需要透明
                    val nodeAlpha = if (isFirst && business == "food_delivery") 0f else 1f
                    
                    Box(
                        modifier = Modifier
                            .size(NODE_SIZE_DP.dp) // 与传统View保持一致的节点大小
                            .background(
                                color = if (completed) primaryColor else primaryColor.copy(alpha = 0.28f),
                                shape = CircleShape
                            )
                            .alpha(nodeAlpha)
                    )
                }
            }
        }

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
