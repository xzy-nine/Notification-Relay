package com.xzyht.notifyrelay.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 连接设备弹窗
 * 返回逻辑由父组件的 NavigationBackHandler 统一处理
 */
@Composable
fun ConnectDeviceDialog(
    showDialog: MutableState<Boolean>,
    device: DeviceInfo?,
    onConnect: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    if (device == null) return

    WindowDialog(show = showDialog.value, modifier = Modifier, title = "连接设备", titleColor = DialogDefaults.titleColor(), summary = "是否连接设备：${device.displayName} \n(${device.uuid})？\n对方将收到认证请求。", summaryColor = DialogDefaults.summaryColor(), backgroundColor = DialogDefaults.backgroundColor(), enableWindowDim = true, onDismissRequest = onDismiss, onDismissFinished = null, outsideMargin = DialogDefaults.outsideMargin, insideMargin = DialogDefaults.insideMargin, defaultWindowInsetsPadding = true, content = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                text = "连接",
                onClick = {
                    onConnect(device)
                },
            )
            TextButton(
                text = "取消",
                onClick = onDismiss,
            )
        }
    })
}
