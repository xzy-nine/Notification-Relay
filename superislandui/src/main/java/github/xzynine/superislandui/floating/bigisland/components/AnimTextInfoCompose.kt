package github.xzynine.superislandui.floating.bigisland.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.SuperIslandImageUtil
import github.xzynine.superislandui.floating.common.formatTimerInfo
import github.xzynine.superislandui.floating.common.resolveIconUrl
import github.xzynine.superislandui.model.components.AnimTextInfo
import kotlinx.coroutines.delay
import notifyrelay.core.util.image.ImageUtils

/**
 * 动画文本信息Compose组件
 */
@Composable
fun AnimTextInfoCompose(
    animTextInfo: AnimTextInfo,
    picMap: Map<String, String>? = null,
) {
    val uiMode = LocalConfiguration.current.uiMode
    val nightMask = uiMode and Configuration.UI_MODE_NIGHT_MASK
    val preferDark = nightMask == Configuration.UI_MODE_NIGHT_YES

    Row(modifier = Modifier.padding(8.dp)) {
        // 左侧图标
        val iconSize = 40.dp
        var finalIconUrl: String?

        // 获取上下文
        val context = LocalContext.current

        // 复用图标选择和解析逻辑
        val iconKey =
            if (preferDark) {
                (
                    animTextInfo.icon.srcDark
                        ?: animTextInfo.icon.src
                )
            } else {
                animTextInfo.icon.src
            }

        // 1. 优先尝试直接用图标 key 加载，使用统一的URL解析工具
        finalIconUrl = resolveIconUrl(picMap, iconKey, context)

        // 2. 如果失败，尝试 picMap 中以 "miui.focus.pic_" 开头的第二个 key
        if (finalIconUrl.isNullOrEmpty() && picMap != null) {
            finalIconUrl = SuperIslandImageUtil.resolveFallbackIconUrl(picMap)
        }

        // 显示图标，使用统一的图片加载工具
        if (!finalIconUrl.isNullOrEmpty()) {
            val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(finalIconUrl)
            painter?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        // 右侧文本区
        val textColumnModifier =
            if (!finalIconUrl.isNullOrEmpty()) {
                Modifier.padding(start = 8.dp).weight(1f)
            } else {
                Modifier.weight(1f)
            }

        Column(modifier = textColumnModifier) {
            // 等价逻辑：
            // major = title ?: formattedTimer；若 major 是时间串则用等宽字体；
            // secondary = content ?: (title!=null && formattedTimer!=null ? formattedTimer : null)

            val hasTimer = animTextInfo.timerInfo != null
            val timerState =
                remember(animTextInfo.timerInfo) {
                    mutableStateOf(
                        animTextInfo.timerInfo?.let { formatTimerInfo(it) },
                    )
                }
            if (hasTimer) {
                // 仅计时进行中（正计时 timerType=1 / 倒计时 timerType=-1）才每秒刷新，暂停/无效类型静态显示
                val isTimerRunning = animTextInfo.timerInfo?.timerType == 1 || animTextInfo.timerInfo?.timerType == -1
                LaunchedEffect(animTextInfo.timerInfo) {
                    if (!isTimerRunning) return@LaunchedEffect
                    while (true) {
                        timerState.value = formatTimerInfo(animTextInfo.timerInfo)
                        delay(1000)
                    }
                }
            }

            val majorText = animTextInfo.title ?: timerState.value
            val majorColor =
                when {
                    preferDark -> ImageUtils.parseColor(animTextInfo.colorTitleDark)
                    else -> ImageUtils.parseColor(animTextInfo.colorTitle)
                } ?: 0xFFFFFFFF.toInt()
            majorText?.let { text ->
                val isTimeLike = text.matches(Regex("[0-9: ]{2,}"))
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(text),
                    fontSize = 15.sp,
                    color = Color(majorColor),
                    maxLines = 1,
                    fontFamily = if (isTimeLike) FontFamily.Monospace else null,
                )
            }

            val secondaryText =
                animTextInfo.content ?: run {
                    if (!animTextInfo.title.isNullOrBlank() && timerState.value != null) timerState.value else null
                }
            val secondaryColor =
                when {
                    preferDark -> ImageUtils.parseColor(animTextInfo.colorContentDark)
                    else -> ImageUtils.parseColor(animTextInfo.colorContent)
                } ?: 0xFFDDDDDD.toInt()
            secondaryText?.let { text ->
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(text),
                    fontSize = 12.sp,
                    color = Color(secondaryColor),
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(name = "动画文本信息", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun AnimTextInfoComposePreview() {
    AnimTextInfoCompose(
        animTextInfo = PreviewData.sampleAnimTextInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "动画文本带计时器", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun AnimTextInfoComposeWithTimerPreview() {
    AnimTextInfoCompose(
        animTextInfo = PreviewData.sampleAnimTextInfoWithTimer,
        picMap = PreviewData.samplePicMap,
    )
}
