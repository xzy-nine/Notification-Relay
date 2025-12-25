package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose.BigIslandCollapsedCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.core.buildBigIslandCollapsedView
import org.json.JSONObject

private const val SUPER_ISLAND_COMPOSE_SUMMARY_KEY = "superisland_compose_summary"

/**
 * 摘要态包装组件，根据开关状态选择使用Compose或AndroidView渲染
 */
@Composable
fun SummaryAndroidView(
    bigIslandJson: JSONObject?,
    picMap: Map<String, String>?,
    fallbackTitle: String?,
    fallbackContent: String?,
    isOverlapping: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 获取开关状态，默认使用View版本
    val useComposeSummary = StorageManager.getBoolean(context, SUPER_ISLAND_COMPOSE_SUMMARY_KEY, false)
    
    // 根据开关状态选择渲染方式
    if (useComposeSummary) {
        // 使用Compose版本渲染
        BigIslandCollapsedCompose(
            bigIsland = bigIslandJson,
            picMap = picMap,
            fallbackTitle = fallbackTitle,
            fallbackContent = fallbackContent,
            isOverlapping = isOverlapping
        )
    } else {
        // 使用传统View版本渲染
        // 使用Compose的key函数，确保bigIslandJson变化时重新创建AndroidView
        key(bigIslandJson?.toString()) {
            AndroidView(
                factory = { ctx ->
                    buildBigIslandCollapsedView(
                        context = ctx,
                        bigIsland = bigIslandJson,
                        picMap = picMap,
                        fallbackTitle = fallbackTitle,
                        fallbackContent = fallbackContent
                    )
                },
                update = { view ->
                    // 当isOverlapping参数变化时，更新视图背景色
                    val density = view.context.resources.displayMetrics.density
                    
                    if (isOverlapping) {
                        // 设置重叠时的红色背景，保持圆角
                        view.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 999f // 圆角
                            setColor(0xEEFF0000.toInt()) // 半透明红色
                            setStroke((density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
                        }
                    } else {
                        // 恢复默认背景色
                        view.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 999f // 圆角
                            setColor(0xCC000000.toInt()) // 半透明黑
                            setStroke((density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
                        }
                    }
                },
                modifier = modifier
            )
        }
    }
}