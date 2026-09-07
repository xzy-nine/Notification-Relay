package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.model.components.MultiProgressInfo
import notifyrelay.core.util.image.ImageUtils
import kotlin.math.max

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF
private const val DEFAULT_NODE_COUNT = 3
private const val NODE_SIZE_DP = 55
private const val PROGRESS_BAR_HEIGHT_DP = 8
private const val POINTER_SIZE_EXTRA_DP = 15 // 指针额外大小
private const val MAX_WIDTH_DP = 366 // 最长宽度
private const val TITLE_MIN_WIDTH_DP = 72 // 标题最小压缩宽度
private const val TITLE_PADDING_DP = 12 // 标题与进度条之间的间距

/**
 * 多进度条Compose组件（组件3）
 * 支持标题显示、节点数量控制、进度条颜色自定义
 */
@Composable
fun MultiProgressCompose(
    multiProgressInfo: MultiProgressInfo,
    modifier: Modifier = Modifier,
    picMap: Map<String, String>? = null,
    business: String? = null,
) {
    val colorValue: Int =
        ImageUtils.parseColor(multiProgressInfo.color) ?: DEFAULT_PRIMARY_COLOR.toInt()
    val primaryColor = Color(colorValue)
    val requestedPoints = multiProgressInfo.points ?: DEFAULT_NODE_COUNT
    val nodeCount = if (requestedPoints == 0) 0 else maxOf(1, requestedPoints) // 处理节点数量为0的情况
    val progressValue = multiProgressInfo.progress.coerceIn(0, 100)
    val segmentCount = maxOf(1, if (nodeCount == 0) 1 else nodeCount - 1) // 最少1段
    val stageFloat = progressValue / 100f * segmentCount
    val pointerIndex = stageFloat.toInt().coerceIn(0, if (nodeCount == 0) 0 else nodeCount - 1)

    // 计算指针位置
    var containerWidth by remember { mutableFloatStateOf(0f) }

    // 容器宽度变化时更新
    fun updateContainerWidth(width: Int) {
        if (width > 0) {
            containerWidth = width.toFloat()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = MAX_WIDTH_DP.dp) // 最长宽度366dp
                .height(NODE_SIZE_DP.dp + if (multiProgressInfo.title.isNotEmpty()) TITLE_PADDING_DP.dp else 0.dp) // 增加高度容纳标题
                .onSizeChanged { updateContainerWidth(it.width) },
        // 移除clipToBounds修饰符，默认不裁剪子组件
    ) {
        // 标题显示
        if (multiProgressInfo.title.isNotEmpty()) {
            Text(
                text = multiProgressInfo.title,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .widthIn(min = TITLE_MIN_WIDTH_DP.dp) // 标题最小压缩宽度72dp
                        .padding(bottom = (TITLE_PADDING_DP / 2).dp),
                style =
                    TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.Black,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 进度条背景层
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PROGRESS_BAR_HEIGHT_DP.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        color = primaryColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(PROGRESS_BAR_HEIGHT_DP.dp / 2),
                    ),
        )

        // 进度条前景层
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction = progressValue / 100f)
                    .height(PROGRESS_BAR_HEIGHT_DP.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        color = primaryColor,
                        shape = RoundedCornerShape(PROGRESS_BAR_HEIGHT_DP.dp / 2),
                    ),
        )

        // 节点行（等距，底部对齐）
        if (nodeCount > 0) { // 只有当节点数量大于0时才显示节点
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(NODE_SIZE_DP.dp)
                        .align(Alignment.BottomCenter)
                        // 节点层级低于指针，满足“forward_pic 最上层”的需求
                        .zIndex(6f),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (index in 0 until nodeCount) {
                    val isLast = index == nodeCount - 1
                    val isCompleted = index <= pointerIndex
                    val isFirst = index == 0
                    // 针对 food_delivery 业务，第一个节点需要透明
                    val nodeAlpha = if (isFirst && business == "food_delivery") 0f else 1f

                    // 根据节点状态选择不同的图标
                    val baseIconKey =
                        when {
                            isLast && isCompleted ->
                                multiProgressInfo.picEnd
                                    ?: multiProgressInfo.picMiddle

                            isLast ->
                                multiProgressInfo.picEndUnselected
                                    ?: multiProgressInfo.picMiddleUnselected

                            isCompleted ->
                                multiProgressInfo.picMiddle
                                    ?: multiProgressInfo.picForwardBox

                            else ->
                                multiProgressInfo.picMiddleUnselected
                                    ?: multiProgressInfo.picForwardBox
                        }

                    Box(
                        modifier =
                            Modifier
                                .size(NODE_SIZE_DP.dp)
                                .alpha(nodeAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 先尝试加载图片
                        val hasIcon = !baseIconKey.isNullOrEmpty()
                        if (hasIcon) {
                            CommonImageCompose(
                                picKey = baseIconKey,
                                picMap = picMap,
                                size = NODE_SIZE_DP.dp,
                                isFocusIcon = false,
                                contentDescription = null,
                            )
                        }

                        // 如果没有图片或图片加载失败，显示默认的圆形指示器
                        if (baseIconKey.isNullOrEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(NODE_SIZE_DP.dp / 2)
                                        .background(
                                            color =
                                                if (isCompleted) {
                                                    primaryColor
                                                } else {
                                                    primaryColor.copy(
                                                        alpha = 0.3f,
                                                    )
                                                },
                                            shape = CircleShape,
                                        ),
                            )
                        }
                    }

                    // 添加等距间距
                    if (index < nodeCount - 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 进度指针（仅在进度在1-99时显示）
            if (progressValue in 1..99) {
                // 直接使用picForward作为进度指示点图片
                // 当picForward为null时，使用picForwardBox作为备选
                val pointerKey = multiProgressInfo.picForward ?: multiProgressInfo.picForwardBox

                // 使用统一的图片加载逻辑，处理picMap查找
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
                val pointerLeftDp =
                    with(density) { (clampedPointerCenterPx - pointerHalfSizePx).toDp() }

                // 指针图应该悬浮在最上面，使用zIndex提升层级
                Box(
                    modifier =
                        Modifier
                            .size(pointerSize)
                            .align(Alignment.BottomStart)
                            // 仅水平偏移，垂直保持贴底，避免超出容器被父视图裁剪
                            .offset(x = pointerLeftDp)
                            // 指针层级最高，确保覆盖节点
                            .zIndex(11f),
                ) {
                    // 先尝试加载指针图片
                    val hasPointerIcon = !pointerKey.isNullOrEmpty()
                    if (hasPointerIcon) {
                        CommonImageCompose(
                            picKey = pointerKey,
                            picMap = picMap,
                            size = pointerSize,
                            isFocusIcon = false,
                            contentDescription = null,
                        )
                    }

                    // 如果没有指针图片或图片加载失败，显示默认的指示点
                    if (pointerKey.isNullOrEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .size(pointerSize)
                                    .background(
                                        color = primaryColor,
                                        shape = CircleShape,
                                    ),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "多进度条50%", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
fun MultiProgressComposePreview() {
    MultiProgressCompose(
        multiProgressInfo = PreviewData.sampleMultiProgressInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "多进度条完成", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 360)
@Composable
fun MultiProgressComposeCompletePreview() {
    MultiProgressCompose(
        multiProgressInfo = PreviewData.sampleMultiProgressInfoComplete,
        picMap = PreviewData.samplePicMap,
    )
}
