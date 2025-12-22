package com.xzyht.notifyrelay.feature.notification.superisland.floating.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding

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
