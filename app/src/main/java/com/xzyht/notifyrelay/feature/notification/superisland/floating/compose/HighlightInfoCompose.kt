package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandImageStore
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.HighlightInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.formatTimerInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.TimerInfo
import com.xzyht.notifyrelay.core.util.DataUrlUtils

/**
 * 高亮信息Compose组件
 */
@Composable
fun HighlightInfoCompose(
    highlightInfo: HighlightInfo,
    picMap: Map<String, String>?
) {
    val density = LocalConfiguration.current.densityDpi / 160f
    val iconKey = selectIconKey(highlightInfo)
    val bitmap = decodeBitmap(picMap, iconKey)
    val hasLeadingIcon = bitmap != null
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = if (highlightInfo.iconOnly) Alignment.CenterVertically else Alignment.CenterVertically
    ) {
        // 图标
        if (bitmap != null) {
            val iconSize = if (highlightInfo.iconOnly) (48 * density).dp else (40 * density).dp
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                contentScale = ContentScale.Fit
            )
        }
        
        // 文本容器
        val textLayoutParams = if (highlightInfo.iconOnly) {
            Modifier.wrapContentWidth()
        } else {
            Modifier.weight(1f)
        }
        
        val textContainerModifier = textLayoutParams
            .let { if (hasLeadingIcon) it.padding(start = 8.dp) else it }
        
        Column(
            modifier = textContainerModifier
        ) {
            // 主要文本
            val primaryText = listOfNotNull(
                highlightInfo.title,
                highlightInfo.content,
                highlightInfo.subContent
            ).firstOrNull { it.isNotBlank() } ?: if (highlightInfo.iconOnly) null else "高亮信息"
            
            primaryText?.let { text ->
                val primaryColor = parseColor(highlightInfo.colorTitle)
                    ?: parseColor(highlightInfo.colorContent)
                    ?: 0xFFFFFFFF.toInt()
                
                Text(
                    text = text,
                    color = Color(primaryColor),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            
            // 状态文本（如“进行中”），与View路径保持一致
            val statusText = highlightInfo.timerInfo
                ?.let { resolveStatusText(highlightInfo) }
                ?.takeIf { it.isNotBlank() && it != primaryText }

            statusText?.let { status ->
                val statusColor = parseColor(highlightInfo.colorSubContent)
                    ?: parseColor(highlightInfo.colorContent)
                    ?: 0xFFDDDDDD.toInt()
                Text(
                    text = status,
                    color = Color(statusColor),
                    fontSize = 12.sp
                )
            }

            // 内容文本
            highlightInfo.content
                ?.takeIf { it.isNotBlank() && it != primaryText }
                ?.let { content ->
                    Text(
                        text = content,
                        color = Color(parseColor(highlightInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            
            // 子内容文本
            highlightInfo.subContent
                ?.takeIf { it.isNotBlank() && it != primaryText }
                ?.let { sub ->
                    Text(
                        text = sub,
                        color = Color(parseColor(highlightInfo.colorSubContent) ?: 0xFF9EA3FF.toInt()),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            
            // 计时器信息
            highlightInfo.timerInfo
                ?.takeIf { !highlightInfo.iconOnly }
                ?.let { timerInfo ->
                    val timerColor = parseColor(highlightInfo.colorTitle) ?: 0xFFFFFFFF.toInt()
                    TimerText(timerInfo, timerColor)
                }
        }
        
        // 大图片区域
        if (!highlightInfo.iconOnly) {
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧大图片
                val leftBitmap = decodeBitmap(picMap, highlightInfo.bigImageLeft) 
                    ?: decodeBitmap(picMap, iconKey)
                leftBitmap?.let {
                    BigAreaImage(it, density)
                }
                
                // 右侧大图片
                val rightBitmap = decodeBitmap(picMap, highlightInfo.bigImageRight) 
                    ?: if (leftBitmap == null) decodeBitmap(picMap, iconKey) else null
                rightBitmap?.let {
                    BigAreaImage(it, density, showLeftMargin = true)
                }
            }
        }
    }
}

/**
 * 大图片区域组件
 */
@Composable
private fun BigAreaImage(
    bitmap: Bitmap,
    density: Float,
    showLeftMargin: Boolean = false
) {
    val size = (44 * density).dp
    val modifier = if (showLeftMargin) {
        Modifier.padding(start = 6.dp)
    } else {
        Modifier
    }
    
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Crop
    )
}

/**
 * 计时器文本组件
 */
@Composable
private fun TimerText(timerInfo: TimerInfo, colorInt: Int) {
    var display by remember {
        mutableStateOf(formatTimerInfo(timerInfo))
    }
    
    // 使用LaunchedEffect和infiniteAnimationFrameMillis来更新计时器
    LaunchedEffect(timerInfo) {
        while (true) {
            display = formatTimerInfo(timerInfo)
            // 每秒更新一次
            kotlinx.coroutines.delay(1000)
        }
    }
    
    Text(
        text = display,
        fontSize = 16.sp,
        color = Color(colorInt)
    )
}

/**
 * 选择图标key
 */
private fun selectIconKey(highlightInfo: HighlightInfo): String? {
    val candidates = mutableListOf<String>()
    candidates.add(highlightInfo.picFunction ?: "")
    candidates.add(highlightInfo.picFunctionDark ?: "")
    candidates.add(highlightInfo.bigImageLeft ?: "")
    candidates.add(highlightInfo.bigImageRight ?: "")
    
    return candidates.firstOrNull { it.isNotBlank() }
}

/**
 * 解码图片
 */
private fun decodeBitmap(picMap: Map<String, String>?, key: String?): android.graphics.Bitmap? {
    if (picMap.isNullOrEmpty() || key.isNullOrBlank()) return null
    val raw = picMap[key] ?: return null
    val resolved = SuperIslandImageStore.resolve(null, raw) ?: raw
    
    return try {
        when {
            resolved.startsWith("data:", ignoreCase = true) -> DataUrlUtils.decodeDataUrlToBitmap(resolved)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

// 与View版相同的状态文本推导逻辑
private fun resolveStatusText(highlightInfo: HighlightInfo): String? {
    val preferred = listOfNotNull(
        highlightInfo.title,
        highlightInfo.content,
        highlightInfo.subContent
    ).firstOrNull { it.contains("进行") }
    if (!preferred.isNullOrBlank()) {
        return preferred
    }
    val base = listOfNotNull(
        highlightInfo.subContent,
        highlightInfo.title,
        highlightInfo.content
    ).firstOrNull { it.isNotBlank() } ?: return null
    return if (base.contains("进行")) base else base + "进行中"
}
