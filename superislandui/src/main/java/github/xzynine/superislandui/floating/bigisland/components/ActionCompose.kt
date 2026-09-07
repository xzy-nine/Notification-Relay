package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.model.components.ActionInfo

/**
 * 操作按钮Compose组件
 */
@Composable
fun ActionCompose(
    actions: List<ActionInfo>,
    picMap: Map<String, String>? = null,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        actions.forEachIndexed { index, actionInfo ->
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                actionInfo.actionTitle?.let { title ->
                    Button(
                        onClick = { /* TODO: 实现按钮点击事件 */ },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "操作按钮", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun ActionComposePreview() {
    ActionCompose(
        actions = PreviewData.sampleActions,
        picMap = PreviewData.samplePicMap,
    )
}
