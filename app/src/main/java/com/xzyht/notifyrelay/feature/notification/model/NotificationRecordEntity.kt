package com.xzyht.notifyrelay.feature.notification.model

// 数据库存储实体
data class NotificationRecordEntity(
    val key: String,
    val packageName: String,
    val appName: String? = null, // 新增字段
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机"
)
