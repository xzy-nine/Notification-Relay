package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.BaseInfo

/**
 * BaseInfo的Compose实现，用于显示主要文本、次要文本等
 */
@Composable
fun BaseInfoCompose(
    baseInfo: BaseInfo,
    picMap: Map<String, String>?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // 主要文本1：关键信息
        baseInfo.title?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 次要文本1：前置描述
        baseInfo.content?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 主要文本2：关键信息
        baseInfo.subTitle?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorSubTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 次要文本2：前置描述
        baseInfo.subContent?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorSubContent) ?: 0xFFDDDDDD.toInt()),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 补充文本
        baseInfo.extraTitle?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorExtraTitle) ?: 0xFF888888.toInt()),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 特殊标签文本
        baseInfo.specialTitle?.let {
            Text(
                text = it,
                color = Color(parseColor(baseInfo.colorSpecialTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(parseColor(baseInfo.colorSpecialBg) ?: 0xFFFF4444.toInt()))
                    .padding(vertical = 2.dp, horizontal = 8.dp)
            )
        }
    }
}
