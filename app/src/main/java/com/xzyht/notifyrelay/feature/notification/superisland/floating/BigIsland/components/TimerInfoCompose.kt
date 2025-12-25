package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.TimerInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.formatTimerInfo
import kotlinx.coroutines.delay

/**
 * 计时器信息Compose组件（简化）
 */
@Composable
fun TimerInfoCompose(timerInfo: TimerInfo, picMap: Map<String, String>? = null) {
    val displayState = remember(timerInfo) { mutableStateOf(formatTimerInfo(timerInfo)) }

    LaunchedEffect(timerInfo) {
        while (true) {
            displayState.value = formatTimerInfo(timerInfo)
            delay(1000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = displayState.value,
            fontSize = 18.sp,
            color = Color.White
        )
    }
}