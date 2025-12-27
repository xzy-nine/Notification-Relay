package com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.param

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.components.ProgressInfoCompose
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.ParamIsland

/**
 * ParamIsland的Compose组件实现
 */
@Composable
fun ParamIslandCompose(
    paramIsland: ParamIsland,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        // SmallIslandArea渲染
        paramIsland.smallIslandArea?.let { smallArea ->
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                smallArea.primaryText?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                smallArea.secondaryText?.let {
                    Text(
                        text = it,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                smallArea.progressInfo?.let { progressInfo ->
                    ProgressInfoCompose(
                        progressInfo = progressInfo,
                    )
                }
            }
        }
        
        // BigIslandArea渲染（简化版，仅显示文本信息）
        paramIsland.bigIslandArea?.let { bigArea ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                bigArea.primaryText?.let {
                    Text(
                        text = it,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                bigArea.secondaryText?.let {
                    Text(
                        text = it,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
