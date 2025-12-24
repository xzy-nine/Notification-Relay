package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.BaseInfo

/**
 * BaseInfo的Compose实现，与传统View功能一致
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
                text = unescapeHtml(it),
                color = Color(parseColor(baseInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal, // 与传统View一致，不使用Bold
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 次要文本1：前置描述
        baseInfo.content?.let {
            Text(
                text = unescapeHtml(it),
                color = Color(parseColor(baseInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
