package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.rememberSuperIslandImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left.AComponent
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left.AImageText1
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left.AImageText5

/**
 * A区组件的Compose实现
 */
@Composable
fun ACompose(
    aComp: AComponent?,
    picMap: Map<String, String>?
) {
    if (aComp == null) return

    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (aComp) {
            is AImageText1 -> {
                // 处理图标
                val hasText = !aComp.title.isNullOrBlank() || !aComp.content.isNullOrBlank()
                val iconSize = if (hasText) 18.dp else 24.dp
                
                // 检查是否有可用的图片资源
                val hasFocusCandidates = picMap?.keys?.any { it.startsWith("miui.focus.pic_", ignoreCase = true) } == true
                val hasIcon = hasFocusCandidates || !aComp.picKey.isNullOrBlank()
                
                if (hasIcon) {
                    // 实现与View版本相同的图标加载逻辑
                    val iconUrl = getFocusIconUrl(picMap, aComp.picKey)
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, aComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
                
                // 文本内容
                val hasTitleOrContent = !aComp.title.isNullOrBlank() || !aComp.content.isNullOrBlank()
                if (hasTitleOrContent) {
                    Column(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(start = 6.dp)
                    ) {
                        aComp.title?.let {
                            Text(
                                text = it,
                                color = if (aComp.showHighlightColor) Color(0xFF40C4FF) else Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = if (aComp.narrowFont) FontFamily.SansSerif else FontFamily.Default
                                ),
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                        }
                        
                        aComp.content?.let {
                            Text(
                                text = it,
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                        fontFamily = if (aComp.narrowFont) FontFamily.SansSerif else FontFamily.Default
                                    ),
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                        }
                    }
                }
            }
            
            is AImageText5 -> {
                // 处理图标
                val hasFocusCandidates = picMap?.keys?.any { it.startsWith("miui.focus.pic_", ignoreCase = true) } == true
                val hasIcon = hasFocusCandidates || !aComp.picKey.isNullOrBlank()
                
                if (hasIcon) {
                    // 实现与View版本相同的图标加载逻辑
                    val iconUrl = getFocusIconUrl(picMap, aComp.picKey)
                    val painter = rememberSuperIslandImagePainter(iconUrl, picMap, aComp.picKey)
                    
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                // 文本内容
                Column(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(start = 6.dp)
                ) {
                    Text(
                        text = aComp.title,
                        color = if (aComp.showHighlightColor) Color(0xFF40C4FF) else Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default
                        ),
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                    
                    aComp.content?.let {
                        Text(
                            text = it,
                            color = Color(0xCCFFFFFF),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontFamily = FontFamily.Default
                            ),
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 实现与View版本相同的图标加载逻辑
 * 1) 若传入了主键（例如 miui.focus.pic_app_icon），应优先使用它
 * 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
 * 3) 按“优先第二位”的规则重排（针对 type=2 无主键场景）：若有第二项，将其放到首位，其余保持原始相对顺序
 * 4) 遍历所有候选图标，尝试加载
 * 5) 所有候选图标都加载失败时，尝试加载 miui.focus.pic_app_icon 作为兜底
 */
private fun getFocusIconUrl(picMap: Map<String, String>?, primaryKey: String?): String? {
    // 1) 若传入了主键，应优先使用它
    if (!primaryKey.isNullOrBlank()) {
        picMap?.get(primaryKey)?.let { return it }
    }

    // 2) 否则/或主键加载失败，再从 picMap 的 miui.focus.pic_* 集合中挑选
    val ordered = picMap?.keys?.asSequence()
        ?.filter { it.startsWith("miui.focus.pic_", ignoreCase = true) && !it.equals("miui.focus.pics", true) }
        ?.toList()
        ?: emptyList()

    // 3) 按“优先第二位”的规则重排：若有第二项，将其放到首位，其余保持原始相对顺序
    val candidates = if (ordered.size >= 2) {
        listOf(ordered[1]) + ordered.filterIndexed { idx, _ -> idx != 1 }
    } else ordered

    // 4) 避免重复尝试主键
    val finalList = if (!primaryKey.isNullOrBlank()) candidates.filterNot { it.equals(primaryKey, true) } else candidates

    // 5) 遍历所有候选图标，尝试加载
    for (k in finalList) {
        picMap?.get(k)?.let { return it }
    }

    // 6) 所有候选图标都加载失败时，尝试加载 miui.focus.pic_app_icon 作为兜底
    if (!primaryKey.equals("miui.focus.pic_app_icon", true)) {
        picMap?.get("miui.focus.pic_app_icon")?.let { return it }
    }

    return null
}
