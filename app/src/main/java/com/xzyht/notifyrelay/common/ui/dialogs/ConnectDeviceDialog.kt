package com.xzyht.notifyrelay.common.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xzyht.notifyrelay.feature.device.data.DeviceInfo

/**
 * 连接设备弹窗
 */
@Composable
fun ConnectDeviceDialog(
    showDialog: MutableState<Boolean>,
    device: DeviceInfo?,
    onConnect: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    if (device == null) return

    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    SuperDialog(
        show = showDialog,
        title = "连接设备",
        summary = "是否连接设备：${device.displayName}？\n对方将收到认证请求。",
        onDismissRequest = onDismiss
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = "连接",
                onClick = {
                    onConnect(device)
                    // 不在这里调用onDismiss，让调用方决定何时关闭弹窗
                }
            )
            TextButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    }
}
