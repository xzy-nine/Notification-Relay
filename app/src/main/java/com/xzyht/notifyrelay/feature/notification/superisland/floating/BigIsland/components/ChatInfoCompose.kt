package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ParamV2
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.rememberSuperIslandImagePainter
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

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
            val painter = rememberSuperIslandImagePainter(avatarUrl)
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

            // 创建圆形进度条 - 使用Miuix的CircularProgressIndicator
            val progressColor = parseColor(progressInfo.colorProgress) ?: 0xFF3482FF.toInt()
            val trackColor = parseColor(progressInfo.colorProgressEnd)
                ?: ((progressColor and 0x00FFFFFF) or (0x33 shl 24))

            // 使用Animatable实现平滑进度动画
            val animatedProgress = remember {
                Animatable(progressInfo.progress.toFloat() / 100f)
            }

            // 当progress变化时，使用动画平滑过渡
            LaunchedEffect(progressInfo.progress) {
                animatedProgress.animateTo(
                    targetValue = progressInfo.progress.toFloat() / 100f,
                    animationSpec = tween(
                        durationMillis = 420, // 动画时长
                        easing = LinearEasing
                    )
                )
            }

            CircularProgressIndicator(
                progress = animatedProgress.value,
                size = 48.dp,
                strokeWidth = 3.5.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = Color(progressColor),
                    backgroundColor = Color(trackColor)
                )
            )
        }

        // 文本内容
        Column(modifier = Modifier.weight(1f)) {
            chatInfo.title?.let {
                Text(
                    text = unescapeHtml(it),
                    color = Color(parseColor(chatInfo.colorTitle) ?: 0xFFFFFFFF.toInt()),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            chatInfo.content?.let {
                Text(
                    text = unescapeHtml(it),
                    color = Color(parseColor(chatInfo.colorContent) ?: 0xFFDDDDDD.toInt()),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}