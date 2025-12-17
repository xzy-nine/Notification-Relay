package com.xzyht.notifyrelay.common.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用配置实体类
 * 对应原StorageManager中的PREFS_GENERAL和部分设备配置
 */
@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String
)
