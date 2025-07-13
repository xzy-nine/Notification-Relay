package com.xzyht.notifyrelay

data class NotificationRecordEntity(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机"
)
