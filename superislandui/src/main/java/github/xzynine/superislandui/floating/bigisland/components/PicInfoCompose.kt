package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.floating.common.SuperIslandImageUtil
import github.xzynine.superislandui.model.templates.PicInfo
import notifyrelay.core.util.image.ImageUtils

/**
 * 图片信息Compose组件
 */
@Composable
fun PicInfoCompose(
    picInfo: PicInfo,
    picMap: Map<String, String>?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图片
        val picKey = picInfo.pic
        CommonImageCompose(
            picKey = picKey,
            picMap = picMap,
            size = 48.dp,
            isFocusIcon = false,
            contentDescription = null,
        )

        // 标题
        picInfo.title?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(picInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
            )
        }
    }
}

@Preview(name = "图片信息类型1", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun PicInfoComposeType1Preview() {
    PicInfoCompose(
        picInfo = PreviewData.samplePicInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "图片信息类型2", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun PicInfoComposeType2Preview() {
    PicInfoCompose(
        picInfo = PreviewData.samplePicInfoType2,
        picMap = PreviewData.samplePicMap,
    )
}
