package com.xzyht.notifyrelay.feature.notification.ui

import androidx.compose.runtime.Composable
import com.xzyht.notifyrelay.feature.notification.ui.filter.UILocalFilter
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地通知过滤设置Pager
 */
@Composable
fun NotificationFilterPager() {
    // NotificationFilterPager 不再接受父级状态参数，
    // 保持与远程过滤相同的模式：由 UILocalFilter 自身读取/写入后端持久化。
    MiuixTheme {
        UILocalFilter()
    }
}
