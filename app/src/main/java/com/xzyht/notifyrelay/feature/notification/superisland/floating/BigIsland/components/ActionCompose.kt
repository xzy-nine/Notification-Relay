package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ActionInfo

/**
 * 操作按钮Compose组件
 */
@Composable
fun ActionCompose(actions: List<ActionInfo>, picMap: Map<String, String>? = null) {
    Column(modifier = Modifier.padding(16.dp)) {
        actions.forEachIndexed { index, actionInfo ->
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                actionInfo.actionTitle?.let { title ->
                    Button(
                        onClick = { /* TODO: 实现按钮点击事件 */ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}