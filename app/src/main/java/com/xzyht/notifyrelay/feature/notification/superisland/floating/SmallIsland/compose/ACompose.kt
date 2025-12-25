package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CommonImageCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CommonTextBlockCompose
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
                
                // 对于焦点图标，即使picKey为空，也应该尝试加载
                // 直接调用CommonImageCompose，让它内部处理图片资源的解析和加载
                CommonImageCompose(
                    picKey = aComp.picKey,
                    picMap = picMap,
                    size = iconSize,
                    isFocusIcon = true,
                    contentDescription = null
                )
                
                // 文本内容
                val hasTitleOrContent = !aComp.title.isNullOrBlank() || !aComp.content.isNullOrBlank()
                if (hasTitleOrContent) {
                    CommonTextBlockCompose(
                        frontTitle = null,
                        title = aComp.title,
                        content = aComp.content,
                        narrow = aComp.narrowFont,
                        highlight = aComp.showHighlightColor,
                        monospace = false
                    )
                }
            }
            
            is AImageText5 -> {
                // 处理图标
                // 直接调用CommonImageCompose，让它内部处理图片资源的解析和加载
                CommonImageCompose(
                    picKey = aComp.picKey,
                    picMap = picMap,
                    size = 18.dp,
                    isFocusIcon = true,
                    contentDescription = null
                )
                
                // 文本内容
                CommonTextBlockCompose(
                    frontTitle = null,
                    title = aComp.title,
                    content = aComp.content,
                    narrow = false,
                    highlight = aComp.showHighlightColor,
                    monospace = false
                )
            }
        }
    }
}