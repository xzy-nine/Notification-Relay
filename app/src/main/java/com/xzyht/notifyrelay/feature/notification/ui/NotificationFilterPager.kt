package com.xzyht.notifyrelay.feature.notification.ui

import androidx.compose.runtime.Composable
import com.xzyht.notifyrelay.feature.notification.ui.filter.UILocalFilter
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本地通知过滤设置Pager
 */
@Composable
fun NotificationFilterPager(
    filterSelf: Boolean = true,
    filterOngoing: Boolean = true,
    filterNoTitleOrText: Boolean = true,
    filterImportanceNone: Boolean = true,
    onFilterSelfChange: (Boolean) -> Unit = {},
    onFilterOngoingChange: (Boolean) -> Unit = {},
    onFilterNoTitleOrTextChange: (Boolean) -> Unit = {},
    onFilterImportanceNoneChange: (Boolean) -> Unit = {}
) {
    // 同步状态到BackendLocalFilter
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterSelf = filterSelf
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterOngoing = filterOngoing
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterNoTitleOrText = filterNoTitleOrText
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterImportanceNone = filterImportanceNone

    MiuixTheme {
        UILocalFilter()
    }
}
