package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.HintInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CommonImageCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil



/**
 * 提示信息Compose组件
 */
@Composable
fun HintInfoCompose(hintInfo: HintInfo, picMap: Map<String, String>? = null) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 图标：使用统一的图片加载工具
        CommonImageCompose(
            picKey = hintInfo.picContent,
            picMap = picMap,
            size = 40.dp,
            isFocusIcon = false,
            contentDescription = null
        )
        
        // 标题
        hintInfo.title?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        // 内容
        hintInfo.content?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
