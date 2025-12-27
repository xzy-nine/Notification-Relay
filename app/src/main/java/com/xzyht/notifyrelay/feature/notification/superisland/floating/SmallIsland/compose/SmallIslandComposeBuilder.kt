package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.json.JSONObject

/**
 * SmallIsland的Compose视图构建器
 * 提供从JSON构建Compose视图的入口函数
 */
suspend fun buildSmallIslandComposeView(
    context: Context,
    bigIsland: JSONObject?,
    picMap: Map<String, String>? = null,
    fallbackTitle: String? = null,
    fallbackContent: String? = null
): ComposeView {
    return ComposeView(context).apply {
        // 浮窗环境默认无 ViewTreeLifecycleOwner，使用分离窗口时销毁更稳妥
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

        setContent {
            BigIslandCollapsedCompose(
                bigIsland = bigIsland,
                picMap = picMap,
                fallbackTitle = fallbackTitle,
                fallbackContent = fallbackContent
            )
        }
    }
}

/**
 * 同步版本的Compose视图构建器
 * 用于不支持协程的场景
 */
fun buildSmallIslandComposeViewSync(
    context: Context,
    bigIsland: JSONObject?,
    picMap: Map<String, String>? = null,
    fallbackTitle: String? = null,
    fallbackContent: String? = null
): ComposeView {
    return ComposeView(context).apply {
        // 浮窗环境默认无 ViewTreeLifecycleOwner，使用分离窗口时销毁更稳妥
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

        setContent {
            BigIslandCollapsedCompose(
                bigIsland = bigIsland,
                picMap = picMap,
                fallbackTitle = fallbackTitle,
                fallbackContent = fallbackContent
            )
        }
    }
}
