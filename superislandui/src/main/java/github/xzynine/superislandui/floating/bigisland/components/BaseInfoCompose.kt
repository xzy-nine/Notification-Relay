package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.CommonImageCompose
import github.xzynine.superislandui.floating.common.SuperIslandImageUtil
import github.xzynine.superislandui.model.templates.BaseInfo
import notifyrelay.core.util.image.ImageUtils

/**
 * BaseInfo的Compose实现
 * 支持文本组件1（type=1）和文本组件2（type=2）
 */
@Composable
fun BaseInfoCompose(
    baseInfo: BaseInfo,
    picMap: Map<String, String>?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .wrapContentHeight(),
    ) {
        val hasSecondary = baseInfo.content != null || baseInfo.subContent != null || baseInfo.picFunction != null
        val hasMain = baseInfo.title != null || baseInfo.subTitle != null || baseInfo.extraTitle != null || baseInfo.specialTitle != null

        if (baseInfo.type == 1) {
            if (hasSecondary) {
                SecondaryTextRow(baseInfo = baseInfo, picMap = picMap)
            }
            if (hasSecondary && hasMain) {
                ContentDivider(baseInfo = baseInfo)
            }
            if (hasMain) {
                MainTextRow(baseInfo = baseInfo)
            }
        } else {
            if (hasMain) {
                MainTextRow(baseInfo = baseInfo)
            }
            if (hasSecondary && hasMain) {
                ContentDivider(baseInfo = baseInfo)
            }
            if (hasSecondary) {
                SecondaryTextRow(baseInfo = baseInfo, picMap = picMap)
            }
        }
    }
}

@Composable
private fun MainTextRow(baseInfo: BaseInfo) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        baseInfo.title?.let {
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        if (baseInfo.showDivider == true && baseInfo.title != null && (baseInfo.subTitle != null || baseInfo.extraTitle != null || baseInfo.specialTitle != null)) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "|",
                color = Color(0xFFDDDDDD),
                fontSize = 14.sp,
                modifier = Modifier.wrapContentWidth(),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        baseInfo.subTitle?.let {
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorSubTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        baseInfo.extraTitle?.let {
            if (baseInfo.subTitle != null) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorExtraTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        baseInfo.specialTitle?.let {
            if (baseInfo.extraTitle != null || baseInfo.subTitle != null) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorSpecialTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .wrapContentWidth()
                        .background(
                            color = Color(ImageUtils.parseColor(baseInfo.colorSpecialBg) ?: 0xFFDDDDDD.toInt()),
                            shape = RoundedCornerShape(4.dp),
                        ).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SecondaryTextRow(
    baseInfo: BaseInfo,
    picMap: Map<String, String>?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        baseInfo.content?.let {
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        baseInfo.subContent?.let {
            if (baseInfo.content != null) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(ImageUtils.parseColor(baseInfo.colorSubContent) ?: 0xFFDDDDDD.toInt()),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.wrapContentWidth(),
            )
        }

        baseInfo.picFunction?.let {
            if (baseInfo.content != null || baseInfo.subContent != null) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            CommonImageCompose(
                picKey = it,
                picMap = picMap,
                size = 24.dp,
                isFocusIcon = false,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun ContentDivider(baseInfo: BaseInfo) {
    if (baseInfo.showContentDivider == true) {
        Spacer(modifier = Modifier.height(8.dp))
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color = Color(0xFFDDDDDD)),
        )
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Preview(name = "文本组件1", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun BaseInfoComposeType1Preview() {
    BaseInfoCompose(
        baseInfo = PreviewData.sampleBaseInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "文本组件2", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun BaseInfoComposeType2Preview() {
    BaseInfoCompose(
        baseInfo = PreviewData.sampleBaseInfoType2,
        picMap = PreviewData.samplePicMap,
    )
}
