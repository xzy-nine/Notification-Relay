package com.xzyht.notifyrelay.feature.notification

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 为包名添加关键词弹窗
 */
@Composable
fun AddKeywordDialog(
    showDialog: MutableState<Boolean>,
    packageName: String,
    initialKeyword: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    var keyword by remember { mutableStateOf(initialKeyword) }

    SuperDialog(
        show = showDialog,
        title = "为包名添加关键词(可选)",
        onDismissRequest = onDismiss
    ) {
        Column {
            Text(packageName, style = textStyles.body2, color = colorScheme.primary)
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = "关键词(可选)",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "确定",
                    onClick = {
                        onConfirm(keyword.trim())
                        onDismiss()
                    }
                )
                TextButton(
                    text = "取消",
                    onClick = onDismiss
                )
            }
        }
    }
}


