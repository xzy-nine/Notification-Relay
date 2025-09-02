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
 * 通知过滤软编码设置Pager
 */
@Composable
fun NotificationFilterPager(
    filterSelf: Boolean = true,
    filterOngoing: Boolean = true,
    filterNoTitleOrText: Boolean = true,
    filterImportanceNone: Boolean = true,
    filterMiPushGroupSummary: Boolean = true,
    filterSensitiveHidden: Boolean = true,
    onFilterSelfChange: (Boolean) -> Unit = {},
    onFilterOngoingChange: (Boolean) -> Unit = {},
    onFilterNoTitleOrTextChange: (Boolean) -> Unit = {},
    onFilterImportanceNoneChange: (Boolean) -> Unit = {},
    onFilterMiPushGroupSummaryChange: (Boolean) -> Unit = {},
    onFilterSensitiveHiddenChange: (Boolean) -> Unit = {}
) {
    // 同步状态到BackendLocalFilter
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterSelf = filterSelf
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterOngoing = filterOngoing
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterNoTitleOrText = filterNoTitleOrText
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterImportanceNone = filterImportanceNone
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterMiPushGroupSummary = filterMiPushGroupSummary
    com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter.filterSensitiveHidden = filterSensitiveHidden

    MiuixTheme {
        UILocalFilter()
    }
}
