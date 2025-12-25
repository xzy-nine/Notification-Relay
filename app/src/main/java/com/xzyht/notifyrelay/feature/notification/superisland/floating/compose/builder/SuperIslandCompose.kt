package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.components.BaseInfoCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.components.HighlightInfoCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.model.ParamV2

/**
 * 超级岛Compose组件的基础接口
 */
@Composable
fun SuperIslandCompose(
    paramV2: ParamV2,
    picMap: Map<String, String>? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(8.dp)
    ) {
        // 根据ParamV2的类型渲染不同的Compose组件
        when {
            paramV2.baseInfo != null -> {
                BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
            }

            paramV2.highlightInfo != null -> {
                HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
            }
            // 其他类型的组件将在后续添加
            else -> {
                // 默认组件，显示未支持的模板
                DefaultSuperIslandCompose()
            }
        }
    }
}

/**
 * 默认的超级岛组件，用于显示未支持的模板
 */
@Composable
fun DefaultSuperIslandCompose() {
    Text(
        text = "未支持的模板",
        color = Color.White,
        modifier = Modifier.fillMaxSize().padding(16.dp)
    )
}