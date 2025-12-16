package com.xzyht.notifyrelay.common.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知记录实体类
 * 对应原notification_records_*.json文件中的数据
 */
@Entity(
    tableName = "notification_records",
    indices = [
        Index(value = ["deviceUuid"]),
        Index(value = ["time"]),
        Index(value = ["packageName"])
    ]
)
data class NotificationRecordEntity(
    @PrimaryKey val key: String,
    val deviceUuid: String,
    val packageName: String,
    val appName: String?,
    val title: String?,
    val text: String?,
    val time: Long,
    val createdAt: Long = System.currentTimeMillis()
)
