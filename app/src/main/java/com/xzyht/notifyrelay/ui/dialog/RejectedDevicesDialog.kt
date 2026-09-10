package com.xzyht.notifyrelay.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 已拒绝设备弹窗
 */
@Composable
fun RejectedDevicesDialog(
    showDialog: MutableState<Boolean>,
    rejectedDevices: List<DeviceInfo>,
    onRestoreDevice: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    WindowBottomSheet(show = showDialog.value, modifier = Modifier, title = "已拒绝设备", startAction = null, endAction = null, backgroundColor = BottomSheetDefaults.backgroundColor(), enableWindowDim = true, cornerRadius = BottomSheetDefaults.cornerRadius, sheetMaxWidth = BottomSheetDefaults.maxWidth, onDismissRequest = onDismiss, onDismissFinished = null, outsideMargin = BottomSheetDefaults.outsideMargin, insideMargin = BottomSheetDefaults.insideMargin, defaultWindowInsetsPadding = true, dragHandleColor = BottomSheetDefaults.dragHandleColor(), allowDismiss = true, enableNestedScroll = true, content = {
        if (rejectedDevices.isEmpty()) {
            Text(
                text = "暂无已拒绝设备",
                style = textStyles.body2,
                color = colorScheme.onSurfaceContainerVariant,
            )
        } else {
            Column {
                rejectedDevices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = device.displayName,
                            style = textStyles.body2,
                            color = colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "恢复",
                            onClick = { onRestoreDevice(device) },
                        )
                    }
                }
            }
        }
    })
}
