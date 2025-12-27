package com.xzyht.notifyrelay.feature.device.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    MiuixTheme.colorScheme
    MiuixTheme.textStyles

    SuperDialog(
        show = showDialog,
        title = "连接设备",
        summary = "是否连接设备：${device.displayName} \n(${device.uuid})？\n对方将收到认证请求。",
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
