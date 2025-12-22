package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.AnimTextInfo

/**
 * 动画文本信息Compose组件
 */
@Composable
fun AnimTextInfoCompose(animTextInfo: AnimTextInfo, picMap: Map<String, String>? = null) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 文本内容
        Row(modifier = Modifier.padding(top = 8.dp)) {
            animTextInfo.title?.let { text ->
                Text(
                    text = text,
                    fontSize = 16.sp,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
        
        // 次要文本
        animTextInfo.content?.let { text ->
            Text(
                text = text,
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
