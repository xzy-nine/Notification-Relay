package github.xzynine.superislandui.floating.smallisland.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.smallisland.right.BComponent
import github.xzynine.superislandui.floating.smallisland.right.BEmpty
import github.xzynine.superislandui.floating.smallisland.right.BFixedWidthDigitInfo
import github.xzynine.superislandui.floating.smallisland.right.BImageText2
import github.xzynine.superislandui.floating.smallisland.right.BImageText3
import github.xzynine.superislandui.floating.smallisland.right.BImageText4
import github.xzynine.superislandui.floating.smallisland.right.BImageText6
import github.xzynine.superislandui.floating.smallisland.right.BPicInfo
import github.xzynine.superislandui.floating.smallisland.right.BProgressTextInfo
import github.xzynine.superislandui.floating.smallisland.right.BSameWidthDigitInfo
import github.xzynine.superislandui.floating.smallisland.right.BTextInfo
import github.xzynine.superislandui.floating.common.CircularProgressCompose
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.floating.common.CommonImagePlaceholder
import github.xzynine.superislandui.floating.common.CommonTextBlockCompose
import github.xzynine.superislandui.floating.common.SuperIslandImageUtil
import github.xzynine.superislandui.floating.common.formatTimerInfo
import github.xzynine.superislandui.floating.common.parseColorSafe
import github.xzynine.superislandui.floating.common.resolveIconUrl

/**
 * B区组件的Compose实现
 */
@Composable
fun BCompose(
    bComp: BComponent,
    picMap: Map<String, String>?,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (bComp) {
            is BImageText2 -> {
                // 图标
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = false,
                    contentDescription = null,
                )

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                    maxWidth = 160.dp,
                )
            }

            is BImageText3 -> {
                // 图标
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = false,
                    contentDescription = null,
                )

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                )
            }

            is BImageText4 -> {
                // 图标 - 仅创建占位，不实际加载图片
                CommonImagePlaceholder(
                    show = !bComp.pic.isNullOrBlank(),
                    size = 18.dp,
                )

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = false,
                    highlight = false,
                    monospace = false,
                )
            }
            is BImageText6 -> {
                // 图标 - 只加载data URL格式的图片
                val iconUrl = resolveIconUrl(picMap, bComp.picKey, context)
                // 只处理data URL格式的图片
                if (iconUrl?.startsWith("data:", ignoreCase = true) == true) {
                    val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)

                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                )
            }

            is BTextInfo -> {
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                )
            }

            is BFixedWidthDigitInfo -> {
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.digit,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true,
                )
            }

            is BSameWidthDigitInfo -> {
                // 处理计时信息或数字
                val timer = bComp.timer
                val isTimerRunning = timer != null && (timer.timerType == 1 || timer.timerType == -1)

                // 标题文本：优先使用 digit，否则格式化 timer（暂停时也直接显示格式化后的值）
                val titleText =
                    if (timer != null) {
                        // 使用 remember + key 确保 timer 变化时重置状态
                        var displayText by remember(timer) {
                            mutableStateOf(formatTimerInfo(timer))
                        }

                        // 仅计时进行中才每秒刷新
                        if (isTimerRunning) {
                            LaunchedEffect(timer) {
                                while (true) {
                                    displayText = formatTimerInfo(timer)
                                    kotlinx.coroutines.delay(1000L)
                                }
                            }
                        }

                        displayText
                    } else {
                        bComp.digit ?: ""
                    }

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = titleText,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true,
                )
            }

            is BProgressTextInfo -> {
                // 进度环 + 图标
                val size = 20.dp
                Box(
                    modifier = Modifier.size(size),
                    contentAlignment = Alignment.Center, // 确保内部元素居中
                ) {
                    // 实现圆形进度环
                    CircularProgressCompose(
                        progress = bComp.progress,
                        colorReach = Color(parseColorSafe(bComp.colorReach, 0xFF3482FF.toInt())),
                        colorUnReach = Color(parseColorSafe(bComp.colorUnReach, 0x33333333)),
                        strokeWidth = 2.5.dp,
                        isClockwise = !bComp.isCCW,
                        size = size,
                    )

                    // 中心图标
                    bComp.picKey?.let { picKey ->
                        val iconUrl = resolveIconUrl(picMap, picKey, context)
                        val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(iconUrl, picMap, picKey)

                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(size - 6.dp)
                                        .align(Alignment.Center),
                            )
                        }
                    }
                }

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                )
            }

            is BPicInfo -> {
                // 图片组件
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 24.dp,
                    isFocusIcon = false,
                    contentDescription = null,
                )
            }

            is BEmpty -> {
                // 空组件，不显示任何内容
            }
        }
    }
}

@Preview(name = "B区文本组件", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun BComposeTextInfoPreview() {
    BCompose(
        bComp =
            BTextInfo(
                title = "B区标题",
                content = "B区内容",
            ),
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "B区图文组件2", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun BComposeImageText2Preview() {
    BCompose(
        bComp =
            BImageText2(
                title = "图文标题",
                content = "图文内容",
                picKey = "icon_key",
            ),
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "B区进度文本组件", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun BComposeProgressTextInfoPreview() {
    BCompose(
        bComp =
            BProgressTextInfo(
                title = "下载中",
                content = "60%",
                progress = 60,
            ),
        picMap = PreviewData.samplePicMap,
    )
}
