package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 默认Compose组件，用于未支持的模板类型
 */
@Composable
fun DefaultCompose(modifier: Modifier = Modifier) {
    Text(
        text = "未支持的模板",
        color = Color.White,
        fontSize = 14.sp,
        modifier = modifier.padding(16.dp)
    )
}

/**
 * 传统展开视图的Compose实现
 */
@Composable
fun DefaultCompose(
    title: String?,
    content: String?,
    image: Bitmap?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "icon",
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        
        Column(
            modifier = Modifier
                .padding(start = if (image != null) 8.dp else 0.dp)
                .weight(1f)
        ) {
            Text(
                text = title ?: "(无标题)",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp),
                maxLines = 1
            )
            Text(
                text = content ?: "(无内容)",
                color = Color(0xFFDDDDDD),
                fontSize = 12.sp,
                maxLines = 2
            )
        }
    }
}
