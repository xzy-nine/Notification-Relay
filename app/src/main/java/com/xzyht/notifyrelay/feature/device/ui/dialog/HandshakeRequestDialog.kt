package com.xzyht.notifyrelay.feature.device.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.device.model.HandshakeRequest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 握手请求弹窗
 */
@Composable
fun HandshakeRequestDialog(
    showDialog: MutableState<Boolean>,
    handshakeRequest: HandshakeRequest?,
    onAccept: (HandshakeRequest) -> Unit,
    onReject: (HandshakeRequest) -> Unit,
    onDismiss: () -> Unit
) {
    if (handshakeRequest == null) return

    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val req = handshakeRequest

    SuperDialog(
        show = showDialog,
        title = "新设备连接请求",
        onDismissRequest = {
            onReject(req)
            onDismiss()
        }
    ) {
        Column {
            Text(
                text = "设备名: ${req.device.displayName}",
                style = textStyles.body2,
                color = colorScheme.onSurfaceContainerVariant
            )
            Text(
                text = "UUID: ${req.device.uuid}",
                style = textStyles.body2,
                color = colorScheme.onSurfaceContainerVariant
            )
            Text(
                text = "IP: ${req.device.ip}  端口: ${req.device.port}",
                style = textStyles.body2,
                color = colorScheme.onSurfaceContainerVariant
            )
            if (!req.publicKey.isNullOrBlank()) {
                Text(
                    text = "公钥: ${req.publicKey}",
                    style = textStyles.body2,
                    color = colorScheme.onSurfaceContainerVariant
                )
            }
            Text(
                text = "是否允许该设备连接？",
                style = textStyles.body2,
                color = colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "同意",
                    onClick = {
                        onAccept(req)
                        onDismiss()
                    }
                )
                TextButton(
                    text = "拒绝",
                    onClick = {
                        onReject(req)
                        onDismiss()
                    }
                )
            }
        }
    }
}
