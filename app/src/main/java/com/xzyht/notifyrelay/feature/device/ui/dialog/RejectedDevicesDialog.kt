package com.xzyht.notifyrelay.feature.device.ui.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo

/**
 * 已拒绝设备弹窗
 */
@Composable
fun RejectedDevicesDialog(
    showDialog: MutableState<Boolean>,
    rejectedDevices: List<DeviceInfo>,
    onRestoreDevice: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    SuperDialog(
        show = showDialog,
        title = "已拒绝设备",
        onDismissRequest = onDismiss
    ) {
        if (rejectedDevices.isEmpty()) {
            Text(
                text = "暂无已拒绝设备",
                style = textStyles.body2,
                color = colorScheme.onSurfaceContainerVariant
            )
        } else {
            Column {
                rejectedDevices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.displayName,
                            style = textStyles.body2,
                            color = colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "恢复",
                            onClick = { onRestoreDevice(device) }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    }
}
