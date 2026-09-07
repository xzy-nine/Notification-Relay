package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * 进度信息Compose组件
 */
@Composable
fun ProgressInfoCompose(
    progressInfo: ProgressInfo,
) {
    val progressColor = ImageUtils.parseColor(progressInfo.colorProgress)?.let { Color(it) } ?: Color(0xFF00FF00)

    LinearProgressIndicator(
        progress = progressInfo.progress.toFloat() / 100f,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(top = 4.dp),
        colors =
            ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = progressColor,
            ),
    )
}

@Preview(name = "进度信息", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun ProgressInfoComposePreview() {
    ProgressInfoCompose(
        progressInfo = PreviewData.sampleProgressInfo,
    )
}
