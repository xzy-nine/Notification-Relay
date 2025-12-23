package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.rememberSuperIslandImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.HintInfo


/**
 * 提示信息Compose组件
 */
@Composable
fun HintInfoCompose(hintInfo: HintInfo, picMap: Map<String, String>? = null) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 图标：使用统一的图片加载工具
        val iconUrl = hintInfo.picContent?.let { key ->
            picMap?.get(key)
        }

        if (!iconUrl.isNullOrEmpty()) {
            val painter = rememberSuperIslandImagePainter(iconUrl)
            painter?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        
        // 标题
        hintInfo.title?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        // 内容
        hintInfo.content?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
