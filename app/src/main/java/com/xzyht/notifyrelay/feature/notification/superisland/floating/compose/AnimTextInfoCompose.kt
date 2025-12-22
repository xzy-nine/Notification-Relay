package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.AnimTextInfo

/**
 * 动画文本信息Compose组件，与传统View功能一致
 */
@Composable
fun AnimTextInfoCompose(animTextInfo: AnimTextInfo, picMap: Map<String, String>? = null) {
    Row(modifier = Modifier.padding(8.dp)) {
        // 左侧图标
        val iconSize = 40.dp
        val iconKey = animTextInfo.icon.src
        val iconUrl = picMap?.get(iconKey)
        
        if (!iconUrl.isNullOrEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(iconUrl),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                contentScale = ContentScale.Fit
            )
        }
        
        // 右侧文本区
        val textColumnModifier = if (!iconUrl.isNullOrEmpty()) {
            Modifier.padding(start = 8.dp).weight(1f)
        } else {
            Modifier.weight(1f)
        }
        
        Column(modifier = textColumnModifier) {
            // 主要文本
            val title = animTextInfo.title
            title?.let {
                Text(
                    text = unescapeHtml(it),
                    fontSize = 15.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1
                )
            }
            
            // 次要文本
            animTextInfo.content?.let {
                Text(
                    text = unescapeHtml(it),
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color(0xFFDDDDDD.toInt()),
                    maxLines = 1
                )
            }
        }
    }
}
