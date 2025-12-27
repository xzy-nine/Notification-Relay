package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.TextButton

/**
 * 文本按钮Compose组件
 */
@Composable
fun TextButtonCompose(textButton: TextButton, picMap: Map<String, String>? = null) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 按钮列表
        textButton.actions.forEach { action ->
            Row(
                modifier = Modifier
                    .clickable {
                        // TODO: handle action (action.actionIntent / action.action)
                    }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = action.actionTitle ?: "",
                    fontSize = 14.sp,
                    color = Color(0xFF4A90E2)
                )
            }
        }
    }
}
