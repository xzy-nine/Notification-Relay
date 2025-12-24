package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2

/**
 * ParamV2主容器Compose组件，根据ParamV2数据选择不同的模板组件进行渲染
 */
@Composable
fun ParamV2Compose(
    paramV2: ParamV2,
    picMap: Map<String, String>?,
    business: String? = null
) {
    val context = LocalContext.current
    
    // 浮窗主容器样式
    Box(
        modifier = Modifier
            .width(320.dp) // 固定宽度，与原有实现一致
            .padding(8.dp)
            .background(
                color = Color.Black.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.large
            )
            .clip(MaterialTheme.shapes.large)
            .padding(16.dp)
    ) {
        // 根据ParamV2数据选择不同的组件进行渲染
        when {
            paramV2.baseInfo != null -> {
                BaseInfoCompose(paramV2.baseInfo, picMap = picMap)
            }
            paramV2.chatInfo != null -> {
                ChatInfoCompose(paramV2, picMap = picMap)
            }
            paramV2.animTextInfo != null -> {
                AnimTextInfoCompose(paramV2.animTextInfo, picMap = picMap)
            }
            paramV2.highlightInfo != null -> {
                HighlightInfoCompose(paramV2.highlightInfo, picMap = picMap)
            }
            paramV2.picInfo != null -> {
                PicInfoCompose(paramV2.picInfo, picMap = picMap)
            }
            paramV2.progressInfo != null -> {
                ProgressCompose(paramV2.progressInfo, picMap = picMap)
            }
            paramV2.multiProgressInfo != null -> {
                MultiProgressCompose(paramV2.multiProgressInfo, picMap = picMap, business)
            }
            paramV2.actions != null -> {
                ActionCompose(paramV2.actions, picMap = picMap)
            }
            paramV2.hintInfo != null -> {
                HintInfoCompose(paramV2.hintInfo, picMap = picMap)
            }
            paramV2.textButton != null -> {
                TextButtonCompose(paramV2.textButton, picMap = picMap)
            }
            else -> {
                // 默认显示：未支持的模板
                DefaultTemplateCompose()
            }
        }
        
        // 进度组件（如果有）
        if (paramV2.progressInfo != null) {
            ProgressCompose(paramV2.progressInfo, picMap)
        }
        
        // 多进度组件（如果有）
        if (paramV2.multiProgressInfo != null) {
            MultiProgressCompose(paramV2.multiProgressInfo, picMap, business)
        }
    }
}

/**
 * 默认模板组件，当没有匹配的模板时显示
 */
@Composable
fun DefaultTemplateCompose() {
    Text(
        text = "未支持的模板",
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium
    )
}
