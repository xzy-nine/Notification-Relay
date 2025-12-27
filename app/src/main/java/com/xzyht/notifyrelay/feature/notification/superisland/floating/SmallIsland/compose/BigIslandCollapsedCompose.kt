package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left.AImageText1
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.left.parseAComponent
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.BEmpty
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.BTextInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.right.parseBComponent
import org.json.JSONObject

/**
 * 超级岛 摘要/收起态总装配渲染器：将 A区 与 B区 的组件解析并组装为一个横向容器
 * Compose实现版本
 */
@Composable
fun BigIslandCollapsedCompose(
    bigIsland: JSONObject?,
    picMap: Map<String, String>? = null,
    fallbackTitle: String? = null,
    fallbackContent: String? = null,
    isOverlapping: Boolean = false
) {
    // 使用真正的圆角形状
    val cornerRadius = 999.dp
    val roundedShape = RoundedCornerShape(cornerRadius)
    
    // 根据重叠状态选择背景色
    val backgroundColor = if (isOverlapping) {
        Color(0xEEFF0000.toInt()) // 半透明红色
    } else {
        Color(0xCC000000.toInt()) // 半透明黑
    }
    
    // 解析A区和B区组件
    var aComp = parseAComponent(bigIsland)
    var bComp = parseBComponent(bigIsland)
    
    // 如果A区组件为空，创建一个默认的AImageText1对象来显示兜底应用图标
    if (aComp == null) {
        aComp = AImageText1(picKey = null)
    }
    
    // 如果 B 为空且存在兜底文本，则用兜底文本填充 B
    val bIsEmptyInitial = (bComp is BEmpty)
    if (bIsEmptyInitial) {
        val titleOrNull = fallbackTitle?.takeIf { it.isNotBlank() }
        val contentOrNull = fallbackContent?.takeIf { it.isNotBlank() }
        if (titleOrNull != null || contentOrNull != null) {
            bComp = BTextInfo(
                title = titleOrNull ?: contentOrNull.orEmpty(),
                content = if (titleOrNull != null) contentOrNull else null
            )
        }
    }
    
    // 主布局：保证长侧显示完全，加宽侧与链接处的空隙宽度
    Box(
        modifier = Modifier
            .shadow(elevation = 6.dp, shape = roundedShape)
            .background(
                color = backgroundColor,
                shape = roundedShape
            )
            .border(
                width = 1.dp,
                color = Color(0x80FFFFFF), // 半透明白色边框
                shape = roundedShape
            )
            .clip(roundedShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .wrapContentWidth()
    ) {
        // 主布局：使用Row实现保证长侧显示完全，加宽侧与链接处的空隙宽度
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // 左侧：A区内容，保证显示完全
            Box(
                modifier = Modifier.wrapContentWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                ACompose(aComp, picMap)
            }
            
            // 只有当B区存在内容时，才显示中间间距和B区
            if (bComp !is BEmpty) {
                // 动态中间间距：根据两侧内容宽度调整
                val dynamicSpacing = 48.dp // 加宽侧与链接处的空隙宽度
                Spacer(modifier = Modifier.width(dynamicSpacing))
                
                // 右侧：B区内容，保证显示完全
                Box(
                    modifier = Modifier.wrapContentWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    BCompose(bComp, picMap)
                }
            }
        }
    }
}
