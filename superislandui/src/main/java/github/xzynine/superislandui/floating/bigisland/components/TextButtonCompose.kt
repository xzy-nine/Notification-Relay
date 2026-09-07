package github.xzynine.superislandui.floating.bigisland.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.xzynine.superislandui.common.PreviewData
import github.xzynine.superislandui.model.components.TextButton

/**
 * 文本按钮Compose组件
 */
@Composable
fun TextButtonCompose(
    textButton: TextButton,
    picMap: Map<String, String>? = null,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 按钮列表
        textButton.actions.forEach { action ->
            Row(
                modifier =
                    Modifier
                        .clickable {
                            // TODO: handle action (action.actionIntent / action.action)
                        }.padding(vertical = 8.dp),
            ) {
                Text(
                    text = action.actionTitle ?: "",
                    fontSize = 14.sp,
                    color = Color(0xFF4A90E2),
                )
            }
        }
    }
}

@Preview(name = "文本按钮", showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
fun TextButtonComposePreview() {
    TextButtonCompose(
        textButton = PreviewData.sampleTextButton,
        picMap = PreviewData.samplePicMap,
    )
}
