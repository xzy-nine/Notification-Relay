package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ProgressInfo

/**
 * 进度信息Compose组件
 */
@Composable
fun ProgressInfoCompose(
    progressInfo: ProgressInfo
) {
    val progressColor = parseColor(progressInfo.colorProgress)?.let { Color(it) } ?: Color(0xFF00FF00)
    
    LinearProgressIndicator(
        progress = progressInfo.progress.toFloat() / 100f,
        color = progressColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .padding(top = 4.dp)
    )
}
