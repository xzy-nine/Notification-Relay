package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import com.xzyht.notifyrelay.common.data.StorageManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 超级岛设置页（用于开启/关闭本地超级岛读取功能并展示简单说明）
 */
@Composable
fun UISuperIslandSettings() {
    val context = LocalContext.current
    val KEY = "superisland_enabled"
    var enabled by remember { mutableStateOf(StorageManager.getBoolean(context, KEY, true)) }

    Surface(color = MiuixTheme.colorScheme.surface) {
        Column {
            Text("超级岛（Super Island）读取")
            Text("说明：此开关控制是否尝试从本机通知中读取小米超级岛数据并转发到远端设备。该功能只读取数据并转发，不会触发任何系统聚焦或白名单行为。")
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                StorageManager.putBoolean(context, KEY, it)
            })
        }
    }
}
