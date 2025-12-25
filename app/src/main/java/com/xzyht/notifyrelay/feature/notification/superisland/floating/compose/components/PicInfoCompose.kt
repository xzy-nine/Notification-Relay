package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.model.PicInfo

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
        if (!picKey.isNullOrEmpty()) {
            val picUrl = picMap?.get(picKey)
            if (!picUrl.isNullOrEmpty()) {
                val painter = rememberSuperIslandImagePainter(picUrl)
                painter?.let {
                    Image(
                        painter = it,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                    )
                }
            }
        }
        
        // 标题
        picInfo.title?.let {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = unescapeHtml(it),
                color = Color(parseColor(picInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                fontSize = 14.sp
            )
        }
    }
}
