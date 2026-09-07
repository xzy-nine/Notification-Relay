package com.xzyht.notifyrelay.feature.notification.superisland.floating.bigisland

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults

// 基础Compose浮窗容器
@Composable
fun ComposeFloatingContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            modifier
                .padding(8.dp),
        cornerRadius = 16.dp,
        colors =
            CardDefaults.defaultColors(
                color = Color(0xEE000000),
            ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

// 文本样式常量
val TitleTextStyle =
    TextStyle(
        fontSize = 16.sp,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )

val ContentTextStyle =
    TextStyle(
        fontSize = 14.sp,
        color = Color.White,
    )

val SubContentTextStyle =
    TextStyle(
        fontSize = 12.sp,
        color = Color(0x80FFFFFF),
    )
