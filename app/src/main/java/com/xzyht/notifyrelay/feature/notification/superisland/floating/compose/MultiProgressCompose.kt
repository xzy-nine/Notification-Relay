package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.MultiProgressInfo
import kotlin.math.max

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF
private const val DEFAULT_NODE_COUNT = 3
private const val NODE_SIZE_DP = 55 // 与传统View保持一致
private const val PROGRESS_BAR_HEIGHT_DP = 8 // 与传统View保持一致
private const val POINTER_SIZE_EXTRA_DP = 15 // 指针额外大小

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
    val requestedPoints = multiProgressInfo.points ?: DEFAULT_NODE_COUNT
    val nodeCount = maxOf(2, requestedPoints)
    val progressValue = multiProgressInfo.progress.coerceIn(0, 100)
    val segmentCount = nodeCount - 1
    val stageFloat = progressValue / 100f * segmentCount
    val pointerIndex = stageFloat.toInt().coerceIn(0, nodeCount - 1)
    val reachedEnd = progressValue >= 100

    // 计算指针位置
    var containerWidth by remember { mutableStateOf(0f) }
    val pointerPosition = containerWidth * (progressValue / 100f)
    
    // 容器宽度变化时更新
    fun updateContainerWidth(width: Int) {
        if (width > 0) {
            containerWidth = width.toFloat()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NODE_SIZE_DP.dp) // 确保有足够高度容纳节点
            .onSizeChanged { updateContainerWidth(it.width) }
            // 移除clipToBounds修饰符，默认不裁剪子组件
    ) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = progressValue / 100f)
                    .height(PROGRESS_BAR_HEIGHT_DP.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        color = primaryColor,
                        shape = RoundedCornerShape(PROGRESS_BAR_HEIGHT_DP.dp / 2)
                    )
            )

            // 节点行（等距，底部对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NODE_SIZE_DP.dp)
                    .align(Alignment.BottomCenter)
                    // 节点层级低于指针，满足“forward_pic 最上层”的需求
                    .zIndex(6f),
                verticalAlignment = Alignment.Bottom
            ) {
                for (index in 0 until nodeCount) {
                    val isLast = index == nodeCount - 1
                    val isCompleted = index <= pointerIndex
                    val isFirst = index == 0
                    // 针对 food_delivery 业务，第一个节点需要透明
                    val nodeAlpha = if (isFirst && business == "food_delivery") 0f else 1f
                    
                    // 根据节点状态选择不同的图标，与View实现保持一致
                    val baseIconKey = when {
                        isLast && isCompleted -> multiProgressInfo.picEnd ?: multiProgressInfo.picMiddle
                        isLast -> multiProgressInfo.picEndUnselected ?: multiProgressInfo.picMiddleUnselected
                        isCompleted -> multiProgressInfo.picMiddle ?: multiProgressInfo.picForwardBox
                        else -> multiProgressInfo.picMiddleUnselected ?: multiProgressInfo.picForwardBox
                    }
                    
                    // 直接将 iconKey 和 picMap 传递给 rememberSuperIslandImagePainter，让它处理 URL 解析和 ref: URL 处理
                    val painter = rememberSuperIslandImagePainter(
                        url = null,
                        picMap = picMap,
                        iconKey = baseIconKey
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(NODE_SIZE_DP.dp)
                            .alpha(nodeAlpha),
                        contentAlignment = Alignment.Center
                    ) {
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(NODE_SIZE_DP.dp)
                            )
                        } else {
                            // 图标加载失败时的 fallback，与View实现保持一致
                            Box(
                                modifier = Modifier
                                    .size(NODE_SIZE_DP.dp)
                                    .background(
                                        color = if (isCompleted) primaryColor else primaryColor.copy(alpha = 0.3f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                    
                    // 添加等距间距，与View版本保持一致
                    if (index < nodeCount - 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 进度指针（仅在进度在1-99时显示）
            if (progressValue in 1..99) {
                // 与View版本保持一致，直接使用picForward作为进度指示点图片
                // 当picForward为null时，使用picForwardBox作为备选
                val pointerKey = multiProgressInfo.picForward ?: multiProgressInfo.picForwardBox
                
                // 使用统一的图片加载逻辑，处理ref: URL和picMap查找
                val painter = rememberSuperIslandImagePainter(
                    url = null,
                    picMap = picMap,
                    iconKey = pointerKey
                )
                val pointerSize = (PROGRESS_BAR_HEIGHT_DP * 4 + POINTER_SIZE_EXTRA_DP).dp
                val density = LocalDensity.current
                // 计算位置统一使用 px，再转换为 dp，避免 px/dp 混用导致的偏移误差
                val safeContainerWidth = max(containerWidth, 1f)
                val ratio = progressValue / 100f
                val pointerSizePx = with(density) { pointerSize.toPx() }
                val pointerHalfSizePx = pointerSizePx / 2f
                val pointerCenterPx = safeContainerWidth * ratio
                val maxValuePx = max(pointerHalfSizePx, safeContainerWidth - pointerHalfSizePx)
                val clampedPointerCenterPx = pointerCenterPx.coerceIn(pointerHalfSizePx, maxValuePx)
                val pointerLeftDp = with(density) { (clampedPointerCenterPx - pointerHalfSizePx).toDp() }
                
                // 指针图应该悬浮在最上面，使用zIndex提升层级
                Box(
                    modifier = Modifier
                        .size(pointerSize)
                        .align(Alignment.BottomStart)
                        // 仅水平偏移，垂直保持贴底，避免超出容器被父视图裁剪
                        .offset(x = pointerLeftDp)
                    // 指针层级最高，确保覆盖节点
                    .zIndex(11f)
                ) {
                    // 总是显示指示点，无论painter是否为null
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(pointerSize)
                        )
                    } else {
                        // 图片加载失败或picForward为null时，显示默认的指示点
                        Box(
                            modifier = Modifier
                                .size(pointerSize)
                                .background(
                                    color = primaryColor,
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        }
}