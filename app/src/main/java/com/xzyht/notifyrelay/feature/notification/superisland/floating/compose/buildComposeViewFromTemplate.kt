package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2

/**
 * 构建Compose UI视图的主函数
 */
suspend fun buildComposeViewFromTemplate(
    context: Context,
    paramV2: ParamV2,
    picMap: Map<String, String>? = null, 
    business: String? = null
): ComposeView {
    return ComposeView(context).apply {
        // Ensure the Compose view disposes with the view tree lifecycle
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SuperIslandComposeRoot {
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
                    paramV2.hintInfo != null -> {
                        HintInfoCompose(paramV2.hintInfo, picMap = picMap)
                    }
                    paramV2.textButton != null -> {
                        TextButtonCompose(paramV2.textButton, picMap = picMap)
                    }
                    paramV2.paramIsland != null -> {
                        ParamIslandCompose(paramV2.paramIsland)
                    }
                    paramV2.actions?.isNotEmpty() == true -> {
                        ActionCompose(paramV2.actions, picMap)
                    }
                    else -> {
                        // 默认模板：未支持的模板类型
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Text(text = "未支持的模板", color = Color.White)
                        }
                    }
                }
                
                // 进度组件
                paramV2.multiProgressInfo?.let {
                    MultiProgressCompose(it, picMap, business)
                } ?: paramV2.progressInfo?.let {
                    ProgressCompose(it, picMap)
                }
            }
        }
    }
}

/**
 * 超级岛Compose根组件
 */
@Composable
fun SuperIslandComposeRoot(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.92f)
            ),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                content()
            }
        }
    }
}
