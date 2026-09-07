package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.floating.common.formatTimerInfo
import github.xzynine.superislandui.model.components.TimerInfo
import kotlinx.coroutines.delay

/**
 * 计时器信息Compose组件（简化）
 */
@Composable
fun TimerInfoCompose(
    timerInfo: TimerInfo,
    picMap: Map<String, String>? = null,
) {
    val displayState = remember(timerInfo) { mutableStateOf(formatTimerInfo(timerInfo)) }

    // 仅计时进行中（正计时 timerType=1 / 倒计时 timerType=-1）才每秒刷新，暂停/无效类型静态显示
    val isTimerRunning = timerInfo.timerType == 1 || timerInfo.timerType == -1
    LaunchedEffect(timerInfo) {
        if (!isTimerRunning) return@LaunchedEffect
        while (true) {
            displayState.value = formatTimerInfo(timerInfo)
            delay(1000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = displayState.value,
            fontSize = 18.sp,
            color = Color.White,
        )
    }
}

@Preview(name = "计时器正计时", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun TimerInfoComposePreview() {
    TimerInfoCompose(
        timerInfo = PreviewData.sampleTimerInfo,
        picMap = PreviewData.samplePicMap,
    )
}

@Preview(name = "计时器倒计时", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun TimerInfoComposeCountdownPreview() {
    TimerInfoCompose(
        timerInfo = PreviewData.sampleTimerInfoCountdown,
        picMap = PreviewData.samplePicMap,
    )
}
