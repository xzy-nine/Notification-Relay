package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.buildBigIslandCollapsedView
import org.json.JSONObject

/**
 * 摘要态AndroidView包装组件
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
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 999f // 圆角
                    setColor(0xEEFF0000.toInt()) // 半透明红色
                    setStroke((density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
                }
            } else {
                // 恢复默认背景色
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 999f // 圆角
                    setColor(0xCC000000.toInt()) // 半透明黑
                    setStroke((density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
                }
            }
        },
        modifier = modifier
    )
}
