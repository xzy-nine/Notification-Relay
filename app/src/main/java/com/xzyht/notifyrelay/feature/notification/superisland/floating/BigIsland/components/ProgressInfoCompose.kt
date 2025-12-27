package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * 进度信息Compose组件
 */
@Composable
fun ProgressInfoCompose(
    progressInfo: ProgressInfo
) {
    val progressColor = SuperIslandImageUtil.parseColor(progressInfo.colorProgress)?.let { Color(it) } ?: Color(0xFF00FF00)
    
    LinearProgressIndicator(
        progress = progressInfo.progress.toFloat() / 100f,
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .padding(top = 4.dp),
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = progressColor
        )
    )
}
