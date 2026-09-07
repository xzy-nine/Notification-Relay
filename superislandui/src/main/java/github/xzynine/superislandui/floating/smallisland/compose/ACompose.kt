package github.xzynine.superislandui.floating.smallisland.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.smallisland.left.AComponent
import github.xzynine.superislandui.floating.smallisland.left.AImageText1
import github.xzynine.superislandui.floating.smallisland.left.AImageText5
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.floating.common.CommonTextBlockCompose

/**
 * A区组件的Compose实现
 */
@Composable
fun ACompose(
    aComp: AComponent?,
    picMap: Map<String, String>?,
) {
    if (aComp == null) return

    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (aComp) {
            is AImageText1 -> {
                // 处理图标
                val hasText = !aComp.title.isNullOrBlank() || !aComp.content.isNullOrBlank()
                val iconSize = if (hasText) 18.dp else 24.dp

                // 对于焦点图标，即使picKey为空，也应该尝试加载
                // 直接调用CommonImageCompose，让它内部处理图片资源的解析和加载
                CommonImageCompose(
                    picKey = aComp.picKey,
                    picMap = picMap,
                    size = iconSize,
                    isFocusIcon = true,
                    contentDescription = null,
                )

                // 文本内容
                val hasTitleOrContent = !aComp.title.isNullOrBlank() || !aComp.content.isNullOrBlank()
                if (hasTitleOrContent) {
                    CommonTextBlockCompose(
                        frontTitle = null,
                        title = aComp.title,
                        content = aComp.content,
                        narrow = aComp.narrowFont,
                        highlight = aComp.showHighlightColor,
                        monospace = false,
                    )
                }
            }

            is AImageText5 -> {
                // 处理图标
                // 直接调用CommonImageCompose，让它内部处理图片资源的解析和加载
                CommonImageCompose(
                    picKey = aComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = true,
                    contentDescription = null,
                )

                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = aComp.title,
                    content = aComp.content,
                    narrow = false,
                    highlight = aComp.showHighlightColor,
                    monospace = false,
                )
            }
        }
    }
}

@Preview(name = "A区组件类型1", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun AComposeType1Preview() {
    ACompose(
        aComp =
            AImageText1(
                title = "A区标题",
                content = "A区内容",
                showHighlightColor = true,
            ),
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "A区组件类型5", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun AComposeType5Preview() {
    ACompose(
        aComp =
            AImageText5(
                title = "A区标题",
                content = "A区内容",
                picKey = "icon_key",
            ),
        picMap = PreviewData.samplePicMap,
    )
}
