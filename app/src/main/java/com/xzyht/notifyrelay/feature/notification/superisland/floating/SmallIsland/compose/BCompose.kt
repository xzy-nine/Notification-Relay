package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.*
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.*

/**
 * B区组件的Compose实现
 */
@Composable
fun BCompose(
    bComp: BComponent,
    picMap: Map<String, String>?
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (bComp) {
            is BImageText2 -> {
                // 图标
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = false,
                    contentDescription = null
                )
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false,
                    maxWidth = 140.dp
                )
            }
            
            is BImageText3 -> {
                // 图标
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = false,
                    contentDescription = null
                )
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BImageText4 -> {
                // 图标 - 仅创建占位，不实际加载图片
                CommonImagePlaceholder(
                    show = !bComp.pic.isNullOrBlank(),
                    size = 18.dp
                )
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = false,
                    highlight = false,
                    monospace = false
                )
            }
            is BImageText6 -> {
                // 图标 - 只加载data URL格式的图片
                val iconUrl = resolveIconUrl(picMap, bComp.picKey)
                // 只处理data URL格式的图片
                if (iconUrl?.startsWith("data:", ignoreCase = true) == true) {
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, bComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.title,
                    content = null,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BTextInfo -> {
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BFixedWidthDigitInfo -> {
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = bComp.digit,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true
                )
            }
            
            is BSameWidthDigitInfo -> {
                // 处理计时信息或数字
                val timer = bComp.timer
                val initialTitleText = bComp.digit ?: timer?.let { formatTimerInfo(it) } ?: ""
                
                // 使用 mutableStateOf 存储标题文本
                var titleText by remember {
                    mutableStateOf(initialTitleText)
                }
                
                if (timer != null) {
                    LaunchedEffect(timer) {
                        // 每秒更新一次时间
                        while (true) {
                            titleText = formatTimerInfo(timer)
                            kotlinx.coroutines.delay(1000L)
                        }
                    }
                } else if (bComp.digit != null) {
                    // 静态数字，直接赋值
                    titleText = bComp.digit
                }
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = titleText,
                    content = bComp.content,
                    narrow = false,
                    highlight = bComp.showHighlightColor,
                    monospace = true
                )
            }
            
            is BProgressTextInfo -> {
                // 进度环 + 图标
                val size = 20.dp
                Box(
                    modifier = Modifier.size(size)
                ) {
                    // 实现圆形进度环
                    CircularProgressCompose(
                        progress = bComp.progress,
                        colorReach = Color(parseColorSafe(bComp.colorReach, 0xFF3482FF.toInt())),
                        colorUnReach = Color(parseColorSafe(bComp.colorUnReach, 0x33333333.toInt())),
                        strokeWidth = 2.5.dp,
                        isClockwise = !bComp.isCCW
                    )
                    
                    // 中心图标
                    bComp.picKey?.let { picKey ->
                        val iconUrl = resolveIconUrl(picMap, picKey)
                        val painter = rememberSuperIslandImagePainter(iconUrl, picMap, picKey)
                        
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(size - 6.dp)
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = bComp.frontTitle,
                    title = bComp.title,
                    content = bComp.content,
                    narrow = bComp.narrowFont,
                    highlight = bComp.showHighlightColor,
                    monospace = false
                )
            }
            
            is BPicInfo -> {
                // 图片组件
                CommonImageCompose(
                    picKey = bComp.picKey,
                    picMap = picMap,
                    size = 24.dp,
                    isFocusIcon = false,
                    contentDescription = null
                )
            }
            
            is BEmpty -> {
                // 空组件，不显示任何内容
            }
        }
    }
}




