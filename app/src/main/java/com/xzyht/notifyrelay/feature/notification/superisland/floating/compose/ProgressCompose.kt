package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ProgressInfo

/**
 * Progress组件的Compose实现
 */
@Composable
fun ProgressCompose(
    progressInfo: ProgressInfo,
    picMap: Map<String, String>?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        LinearProgressIndicator(
            progress = progressInfo.progress.toFloat() / 100f,
            color = Color(parseColor(progressInfo.colorProgress) ?: 0xFF4CAF50.toInt()),
            trackColor = Color(parseColor(progressInfo.colorProgressEnd) ?: 0xFF8BC34A.toInt()),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
