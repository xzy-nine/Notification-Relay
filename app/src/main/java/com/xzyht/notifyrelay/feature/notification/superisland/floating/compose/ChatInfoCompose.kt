package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.compose.rememberSuperIslandImagePainter
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ChatInfo
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2

/**
 * 聊天信息Compose组件，与传统View功能一致
 */
@Composable
fun ChatInfoCompose(paramV2: ParamV2, picMap: Map<String, String>?) {
    val chatInfo = paramV2.chatInfo ?: return
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp), // 与传统View一致
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像
        val avatarUrl = chatInfo.picProfile?.let { picMap?.get(it) }
        if (!avatarUrl.isNullOrEmpty()) {
            val painter = rememberSuperIslandImagePainter(avatarUrl)
            painter?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
        
        // 文本内容
        Column(modifier = Modifier.weight(1f)) {
            chatInfo.title?.let {
                Text(
                    text = unescapeHtml(it),
                    color = Color(parseColor(chatInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                    fontSize = 14.sp, // 与传统View一致
                    fontWeight = FontWeight.Normal, // 与传统View一致
                    modifier = Modifier.padding(start = 8.dp) // 与传统View一致
                )
            }
            
            chatInfo.content?.let {
                Text(
                    text = unescapeHtml(it),
                    color = Color(parseColor(chatInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                    fontSize = 12.sp, // 与传统View一致
                    modifier = Modifier.padding(start = 8.dp) // 与传统View一致
                )
            }
        }
    }
}
