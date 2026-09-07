package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.floating.common.SuperIslandImageUtil
import github.xzynine.superislandui.floating.common.formatTimerInfo
import github.xzynine.superislandui.model.components.TimerInfo
import github.xzynine.superislandui.model.templates.HighlightInfo
import kotlinx.coroutines.delay
import notifyrelay.core.util.image.ImageUtils

/**
 * 高亮信息Compose组件
 */
@Composable
fun HighlightInfoCompose(
    highlightInfo: HighlightInfo,
    picMap: Map<String, String>?,
) {
    val density = LocalConfiguration.current.densityDpi / 160f
    val iconKey = selectIconKey(highlightInfo)
    val hasIcon = !iconKey.isNullOrEmpty()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标
        if (hasIcon) {
            val iconSize = if (highlightInfo.iconOnly) (48 * density).dp else (40 * density).dp
            CommonImageCompose(
                picKey = iconKey,
                picMap = picMap,
                size = iconSize,
                isFocusIcon = false,
                contentDescription = null,
            )
        }

        // 文本容器
        val textLayoutParams =
            if (highlightInfo.iconOnly) {
                Modifier.wrapContentWidth()
            } else {
                Modifier.weight(1f)
            }
        val textContainerModifier = textLayoutParams.let { if (hasIcon) it.padding(start = 8.dp) else it }

        Column(modifier = textContainerModifier) {
            val timerLabel =
                highlightInfo.timerInfo?.let { info ->
                    if (info.timerType <= 0) "倒计时" else "计时器"
                }

            // 主要文本
            val primaryTextRaw =
                listOfNotNull(
                    highlightInfo.title,
                    highlightInfo.content,
                    highlightInfo.subContent,
                ).firstOrNull { it.isNotBlank() }
                    ?: timerLabel
                    ?: if (highlightInfo.iconOnly) null else "高亮信息"
            primaryTextRaw?.let {
                val primaryColor =
                    ImageUtils.parseColor(highlightInfo.colorTitle)
                        ?: ImageUtils.parseColor(highlightInfo.colorContent)
                        ?: 0xFFFFFFFF.toInt()
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                    color = Color(primaryColor),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // 状态文本（如“进行中”）
            val statusText =
                highlightInfo.timerInfo
                    ?.let { resolveStatusText(highlightInfo) }
                    ?.takeIf { it.isNotBlank() && it != primaryTextRaw }
                    ?: timerLabel?.takeIf { it.isNotBlank() && it != primaryTextRaw }
            statusText?.let { status ->
                val statusColor =
                    ImageUtils.parseColor(highlightInfo.colorSubContent)
                        ?: ImageUtils.parseColor(highlightInfo.colorContent)
                        ?: 0xFFDDDDDD.toInt()
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(status),
                    color = Color(statusColor),
                    fontSize = 12.sp,
                )
            }

            // 内容文本
            highlightInfo.content
                ?.takeIf { it.isNotBlank() && it != primaryTextRaw }
                ?.let { content ->
                    Text(
                        text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(content),
                        color = Color(ImageUtils.parseColor(highlightInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

            // 子内容文本
            highlightInfo.subContent
                ?.takeIf { it.isNotBlank() && it != primaryTextRaw }
                ?.let { sub ->
                    Text(
                        text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(sub),
                        color = Color(ImageUtils.parseColor(highlightInfo.colorSubContent) ?: 0xFF9EA3FF.toInt()),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

            // 计时器信息
            highlightInfo.timerInfo
                ?.takeIf { !highlightInfo.iconOnly }
                ?.let { timerInfo ->
                    val timerColor = ImageUtils.parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt()
                    TimerText(timerInfo, timerColor)
                }
        }

        // 大图片区域
        if (!highlightInfo.iconOnly) {
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧图片
                val leftImageUrl = picMap?.get(highlightInfo.bigImageLeft) ?: picMap?.get(iconKey)
                val leftPainter = leftImageUrl?.let { SuperIslandImageUtil.rememberSuperIslandImagePainter(it) }
                leftPainter?.let { BigAreaImage(it, density) }

                // 右侧图片
                val rightImageUrl =
                    picMap?.get(highlightInfo.bigImageRight)
                        ?: if (leftPainter == null) picMap?.get(iconKey) else null
                val rightPainter = rightImageUrl?.let { SuperIslandImageUtil.rememberSuperIslandImagePainter(it) }
                rightPainter?.let { BigAreaImage(it, density, showLeftMargin = true) }
            }
        }
    }
}

@Composable
private fun BigAreaImage(
    painter: Painter,
    density: Float,
    showLeftMargin: Boolean = false,
) {
    val size = (44 * density).dp
    val modifier = if (showLeftMargin) Modifier.padding(start = 6.dp) else Modifier

    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

@Composable
private fun TimerText(
    timerInfo: TimerInfo,
    colorInt: Int,
) {
    val displayState = remember(timerInfo) { mutableStateOf(formatTimerInfo(timerInfo)) }
    // 仅计时进行中（正计时 timerType=1 / 倒计时 timerType=-1）才每秒刷新，暂停/无效类型静态显示
    val isTimerRunning = timerInfo.timerType == 1 || timerInfo.timerType == -1
    LaunchedEffect(timerInfo) {
        if (!isTimerRunning) return@LaunchedEffect
        while (true) {
            displayState.value = formatTimerInfo(timerInfo)
            delay(1000)
        }
    }
    Text(
        text = displayState.value,
        fontSize = 16.sp,
        color = Color(colorInt),
    )
}

private fun selectIconKey(highlightInfo: HighlightInfo): String? {
    val candidates = mutableListOf<String>()
    candidates.add(highlightInfo.picFunction ?: "")
    candidates.add(highlightInfo.picFunctionDark ?: "")
    candidates.add(highlightInfo.bigImageLeft ?: "")
    candidates.add(highlightInfo.bigImageRight ?: "")
    return candidates.firstOrNull { it.isNotBlank() }
}

// 状态文本推导逻辑
private fun resolveStatusText(highlightInfo: HighlightInfo): String? {
    val preferred =
        listOfNotNull(
            highlightInfo.title,
            highlightInfo.content,
            highlightInfo.subContent,
        ).firstOrNull { it.contains("进行") }
    if (!preferred.isNullOrBlank()) return preferred

    val base =
        listOfNotNull(
            highlightInfo.subContent,
            highlightInfo.title,
            highlightInfo.content,
        ).firstOrNull { it.isNotBlank() } ?: return null
    return if (base.contains("进行")) base else base + "进行中"
}

@Preview(name = "高亮信息", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun HighlightInfoComposePreview() {
    HighlightInfoCompose(
        highlightInfo = PreviewData.sampleHighlightInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "高亮信息带计时器", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun HighlightInfoComposeWithTimerPreview() {
    HighlightInfoCompose(
        highlightInfo = PreviewData.sampleHighlightInfoWithTimer,
        picMap = PreviewData.samplePicMap,
    )
}
