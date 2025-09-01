package com.xzyht.notifyrelay.common.data

import com.xzyht.notifyrelay.common.data.NotificationAction

// 通知记录数据模型
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appName: String? = null, // 新增字段
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机",
    val actions: List<NotificationAction> = emptyList(),
    val smallIconResId: Int? = null
)
