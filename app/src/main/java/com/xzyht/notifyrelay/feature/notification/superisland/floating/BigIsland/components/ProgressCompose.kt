package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ProgressInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

/**
 * Progress组件的Compose实现，与传统View功能一致
 */
@Composable
fun ProgressCompose(
    progressInfo: ProgressInfo,
    picMap: Map<String, String>?
) {
    // 与传统View保持一致，只设置进度条颜色，不设置轨道颜色
    val progressColor = Color(SuperIslandImageUtil.parseColor(progressInfo.colorProgress) ?: 0xFF00FF00.toInt())
    
    LinearProgressIndicator(
        progress = progressInfo.progress.toFloat() / 100f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 0.dp, end = 0.dp, bottom = 0.dp), // 与传统View保持一致的margin
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = progressColor
        )
    )
}
