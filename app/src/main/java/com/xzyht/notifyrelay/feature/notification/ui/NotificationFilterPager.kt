package com.xzyht.notifyrelay.feature.notification.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Checkbox
import com.xzyht.notifyrelay.feature.notification.ui.filter.UILocalFilter

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
