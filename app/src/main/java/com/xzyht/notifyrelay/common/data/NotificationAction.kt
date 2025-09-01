package com.xzyht.notifyrelay.common.data

import android.app.PendingIntent
import android.graphics.drawable.Icon

// 通知动作数据模型
data class NotificationAction(
    val icon: Icon? = null,
    val svgResId: Int? = null,
    val title: String?,
    val intent: PendingIntent?
)
