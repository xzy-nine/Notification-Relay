package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.param

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 基础Compose浮窗容器
@Composable
fun ComposeFloatingContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEE000000)
        ),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

// 文本样式常量
val TitleTextStyle = TextStyle(
    fontSize = 16.sp,
    color = Color.White,
    fontWeight = FontWeight.Bold
)

val ContentTextStyle = TextStyle(
    fontSize = 14.sp,
    color = Color.White
)

val SubContentTextStyle = TextStyle(
    fontSize = 12.sp,
    color = Color(0x80FFFFFF)
)
