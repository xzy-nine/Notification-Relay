package com.xzyht.notifyrelay.common.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 设备实体类
 * 对应原authenticatedDevices中的数据
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val uuid: String,
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean,
    val displayName: String,
    val lastIp: String,
    val lastPort: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
