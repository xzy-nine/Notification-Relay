package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose.BigIslandCollapsedCompose
import org.json.JSONObject

/**
 * 摘要态包装组件，直接使用Compose渲染
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
    // 直接使用Compose版本渲染
    BigIslandCollapsedCompose(
        bigIsland = bigIslandJson,
        picMap = picMap,
        fallbackTitle = fallbackTitle,
        fallbackContent = fallbackContent,
        isOverlapping = isOverlapping
    )
}