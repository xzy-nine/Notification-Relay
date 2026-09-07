package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.model.components.ProgressInfo
import notifyrelay.core.util.image.ImageUtils
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

/**
 * Progress组件的Compose实现，与传统View功能一致
 */
@Composable
fun ProgressCompose(
    progressInfo: ProgressInfo,
    picMap: Map<String, String>?,
) {
    // 与传统View保持一致，只设置进度条颜色，不设置轨道颜色
    val progressColor = Color(ImageUtils.parseColor(progressInfo.colorProgress) ?: 0xFF00FF00.toInt())

    LinearProgressIndicator(
        progress = progressInfo.progress.toFloat() / 100f,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 0.dp, end = 0.dp, bottom = 0.dp),
        colors =
            ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = progressColor,
            ),
    )
}

@Preview(name = "进度条60%", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun ProgressComposePreview() {
    ProgressCompose(
        progressInfo = PreviewData.sampleProgressInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "进度条25%", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun ProgressComposeLowPreview() {
    ProgressCompose(
        progressInfo = PreviewData.sampleProgressInfoLow,
        picMap = PreviewData.samplePicMap,
    )
}
