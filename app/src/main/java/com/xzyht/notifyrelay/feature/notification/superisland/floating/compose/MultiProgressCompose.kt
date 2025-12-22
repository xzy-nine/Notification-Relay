package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.MultiProgressInfo

private const val DEFAULT_PRIMARY_COLOR = 0xFF0ABAFF
private const val DEFAULT_NODE_COUNT = 3

/**
 * 多进度条Compose组件（暂时精简实现以避免编译复杂性）
 */
@Composable
fun MultiProgressCompose(
    multiProgressInfo: MultiProgressInfo,
    picMap: Map<String, String>? = null,
    business: String? = null,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Text(
        text = multiProgressInfo.title ?: "",
        color = androidx.compose.ui.graphics.Color.White,
        fontSize = 12.sp,
        modifier = modifier.padding(8.dp)
    )
}
