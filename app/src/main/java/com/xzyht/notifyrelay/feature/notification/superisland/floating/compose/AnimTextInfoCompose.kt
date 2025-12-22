package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.AnimTextInfo

/**
 * 动画文本信息Compose组件，与传统View功能一致
 */
@Composable
fun AnimTextInfoCompose(animTextInfo: AnimTextInfo, picMap: Map<String, String>? = null) {
    Row(modifier = Modifier.padding(8.dp)) {
        // 左侧图标
        val iconSize = 40.dp
        val iconKey = animTextInfo.icon.src
        val iconUrl = picMap?.get(iconKey)
        
        if (!iconUrl.isNullOrEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(iconUrl),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                contentScale = ContentScale.Fit
            )
        }
        
        // 右侧文本区
        val textColumnModifier = if (!iconUrl.isNullOrEmpty()) {
            Modifier.padding(start = 8.dp).weight(1f)
        } else {
            Modifier.weight(1f)
        }
        
        Column(modifier = textColumnModifier) {
            // View 路径等价逻辑：
            // major = title ?: formattedTimer；若 major 是时间串则用等宽字体；
            // secondary = content ?: (title!=null && formattedTimer!=null ? formattedTimer : null)

            // 计时显示状态，按秒刷新
            val hasTimer = animTextInfo.timerInfo != null
            val timerState = remember(animTextInfo.timerInfo) {
                mutableStateOf(
                    animTextInfo.timerInfo?.let { com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.formatTimerInfo(it) }
                )
            }
            if (hasTimer) {
                LaunchedEffect(animTextInfo.timerInfo) {
                    while (true) {
                        timerState.value = animTextInfo.timerInfo?.let {
                            com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.formatTimerInfo(it)
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            val majorText = animTextInfo.title ?: timerState.value
            val majorColor = parseColor(animTextInfo.colorTitle) ?: 0xFFFFFFFF.toInt()
            majorText?.let { text ->
                val isTimeLike = text.matches(Regex("[0-9: ]{2,}"))
                Text(
                    text = unescapeHtml(text),
                    fontSize = 15.sp,
                    color = androidx.compose.ui.graphics.Color(majorColor),
                    maxLines = 1,
                    fontFamily = if (isTimeLike) FontFamily.Monospace else null
                )
            }

            val secondaryText = animTextInfo.content ?: run {
                if (!animTextInfo.title.isNullOrBlank() && timerState.value != null) timerState.value else null
            }
            val secondaryColor = parseColor(animTextInfo.colorContent) ?: 0xFFDDDDDD.toInt()
            secondaryText?.let { text ->
                Text(
                    text = unescapeHtml(text),
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color(secondaryColor),
                    maxLines = 1
                )
            }
        }
    }
}
