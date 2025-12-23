package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.content.Context
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
        modifier = modifier
    )
}
