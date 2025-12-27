package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.CircularProgressCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil

/**
 * 聊天信息Compose组件
 */
@Composable
fun ChatInfoCompose(paramV2: ParamV2, picMap: Map<String, String>?) {
    val chatInfo = paramV2.chatInfo ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像
        val avatarUrl = chatInfo.picProfile?.let { picMap?.get(it) }
        if (!avatarUrl.isNullOrEmpty()) {
            val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(avatarUrl)
            painter?.let {
                Image(
                    painter = it,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }
        }

        // 圆形进度条（从actions中获取）
        val actionWithProgress = paramV2.actions?.firstOrNull { it.progressInfo != null }
        val progressInfo = actionWithProgress?.progressInfo ?: paramV2.progressInfo

        if (progressInfo != null) {
            // 添加8dp的margin
            Spacer(modifier = Modifier.width(8.dp))

            // 创建圆形进度条 - 使用common目录下的通用组件
            val progressColor = SuperIslandImageUtil.parseColor(progressInfo.colorProgress) ?: 0xFF3482FF.toInt()
            val trackColor = SuperIslandImageUtil.parseColor(progressInfo.colorProgressEnd)
                ?: ((progressColor and 0x00FFFFFF) or (0x33 shl 24))

            // 使用通用的CircularProgressCompose组件（内部已处理动画）
            CircularProgressCompose(
                progress = progressInfo.progress,
                colorReach = Color(progressColor),
                colorUnReach = Color(trackColor),
                strokeWidth = 3.5.dp,
                isClockwise = true,
                size = 48.dp
            )
        }

        // 文本内容
        Column(modifier = Modifier.weight(1f)) {
            chatInfo.title?.let {
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                    color = Color(SuperIslandImageUtil.parseColor(chatInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            chatInfo.content?.let {
                Text(
                    text = SuperIslandImageUtil.parseSimpleHtmlToAnnotatedString(it),
                    color = Color(SuperIslandImageUtil.parseColor(chatInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}