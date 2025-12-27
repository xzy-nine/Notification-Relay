package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CommonImageCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.PicInfo


/**
 * 图片信息Compose组件
 */
@Composable
fun PicInfoCompose(picInfo: PicInfo, picMap: Map<String, String>?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图片
        val picKey = picInfo.pic
        CommonImageCompose(
            picKey = picKey,
            picMap = picMap,
            size = 48.dp,
            isFocusIcon = false,
            contentDescription = null
        )
        
        // 标题
        picInfo.title?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                color = Color(SuperIslandImageUtil.parseColor(picInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp
            )
        }
    }
}
