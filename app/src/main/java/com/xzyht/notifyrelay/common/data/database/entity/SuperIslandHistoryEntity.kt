package com.xzyht.notifyrelay.common.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 超级岛历史记录实体类
 * 用于持久化超级岛历史记录数据
 */
@Entity(tableName = "super_island_history")
data class SuperIslandHistoryEntity(
    @PrimaryKey(autoGenerate = false) val id: Long,
    val sourceDeviceUuid: String? = null,
    val originalPackage: String? = null,
    val mappedPackage: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val paramV2Raw: String? = null,
    val picMap: String = "{}", // 存储为JSON字符串
    val rawPayload: String? = null
)
