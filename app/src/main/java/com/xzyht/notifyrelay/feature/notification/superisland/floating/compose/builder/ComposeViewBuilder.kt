package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.builder

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.model.ParamV2

/**
 * 创建Compose视图的构建器
 */
suspend fun buildComposeViewFromTemplateSimple(
    context: Context,
    paramV2: ParamV2,
    picMap: Map<String, String>? = null,
    business: String? = null
): ComposeView {
    return ComposeView(context).apply {
        // 浮窗环境默认无 ViewTreeLifecycleOwner，使用分离窗口时销毁更稳妥
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

        setContent {
            SuperIslandCompose(paramV2, picMap)
        }
    }
}