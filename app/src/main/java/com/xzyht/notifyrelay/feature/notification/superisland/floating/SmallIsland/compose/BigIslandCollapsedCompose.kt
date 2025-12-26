package com.xzyht.notifyrelay.feature.notification.superisland.floating.SmallIsland.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
    
    // 胶囊容器
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
    ) {
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
        
        // 主布局：A区 + 弹性占位 + B区
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val aExists = aComp != null
            val bIsEmpty = bComp is BEmpty
            
            // 左侧胶囊头（若 A 不存在）
            if (!aExists) {
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            // 左侧包裹（A 左对齐）
            Box(
                modifier = Modifier.wrapContentWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                ACompose(aComp, picMap)
            }
            
            // 中间弹性占位（将右侧内容推向右边界）
            Spacer(modifier = Modifier.weight(1f))
            
            // 右侧包裹（B 右对齐）
            if (!bIsEmpty) {
                Box(
                    modifier = Modifier.wrapContentWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    BCompose(bComp, picMap)
                }
            } else {
                // 右侧胶囊头（若 B 为空）
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
    }
}
