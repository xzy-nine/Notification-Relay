package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.parseColor
import com.xzyht.notifyrelay.feature.notification.superisland.floating.bigislandarea.unescapeHtml
import com.xzyht.notifyrelay.feature.notification.superisland.floating.renderer.ParamV2
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

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
        
        // 圆形进度条（从actions中获取）
        val actionWithProgress = paramV2.actions?.firstOrNull { it.progressInfo != null }
        val progressInfo = actionWithProgress?.progressInfo ?: paramV2.progressInfo
        
        if (progressInfo != null) {
            // 与View渲染一致，添加8dp的margin
            Spacer(modifier = Modifier.width(8.dp))
            
            // 创建圆形进度条 - 使用Miuix的CircularProgressIndicator
            val progressColor = parseColor(progressInfo.colorProgress) ?: 0xFF3482FF.toInt()
            val trackColor = parseColor(progressInfo.colorProgressEnd)
                ?: ((progressColor and 0x00FFFFFF) or (0x33 shl 24))
            
            // 使用Animatable实现平滑进度动画
            val animatedProgress = remember { 
                androidx.compose.animation.core.Animatable(progressInfo.progress.toFloat() / 100f)
            }
            
            // 当progress变化时，使用动画平滑过渡
            LaunchedEffect(progressInfo.progress) {
                animatedProgress.animateTo(
                    targetValue = progressInfo.progress.toFloat() / 100f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 420, // 与View渲染保持一致的动画时长
                        easing = androidx.compose.animation.core.LinearEasing
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
