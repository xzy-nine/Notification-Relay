package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.common.AutoFitText
import com.xzyht.notifyrelay.feature.notification.superisland.common.AutoScrollText
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.MediaSessionData
import com.xzyht.notifyrelay.feature.notification.superisland.floating.common.SuperIslandImageUtil
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COVER_SIZE = 80

@Composable
fun MediaIslandCompose(
    mediaSession: MediaSessionData,
    isExpanded: Boolean = true,
    onCollapse: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    ExpandedMediaIsland(
        mediaSession = mediaSession,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onClose = onClose
    )
}

@Composable
private fun ExpandedMediaIsland(
    mediaSession: MediaSessionData,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            val painter = SuperIslandImageUtil.rememberSuperIslandImagePainter(
                url = mediaSession.coverUrl
            )
            
            // 只有在有封面时才显示封面图片
            if (painter != null) {
                Box(
                    modifier = Modifier
                        .size(COVER_SIZE.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = "歌曲封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                // 只有在有封面时才添加间距
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                AutoScrollText(
                    text = mediaSession.title.ifBlank { "未知标题" },
                    style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFFFFFFFF.toInt()),
                    baseSpeedPxPerSec = 150f,
                    pauseMillis = 0
                )

                Spacer(modifier = Modifier.height(4.dp))

                AutoFitText(
                    text = mediaSession.text.ifBlank { "未知艺术家" },
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFDDDDDD.toInt()),
                    minTextSize = 10f
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "来自: ${mediaSession.deviceName}",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFDDDDDD.toInt()),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clickable { onPrevious() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "上一首",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }

            Box(
                modifier = Modifier
                    .clickable { onPlayPause() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "播放/暂停",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }

            Box(
                modifier = Modifier
                    .clickable { onNext() }
                    .padding(8.dp)
            ) {
                Text(
                    text = "下一首",
                    style = MiuixTheme.textStyles.body2,
                    color = Color(0xFFFFFFFF.toInt())
                )
            }
        }
    }
}
