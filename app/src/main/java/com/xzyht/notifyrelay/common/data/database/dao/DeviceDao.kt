package com.xzyht.notifyrelay.common.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity

/**
 * 设备DAO接口
 * 定义设备信息的增删改查操作
 */
@Dao
interface DeviceDao {
    /**
     * 获取所有设备
     */
    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceEntity>
    
    /**
     * 根据UUID获取设备
     */
    @Query("SELECT * FROM devices WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): DeviceEntity?
    
    /**
     * 插入或更新设备
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: DeviceEntity)
    
    /**
     * 批量插入或更新设备
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<DeviceEntity>)
    
    /**
     * 删除设备
     */
    @Delete
    suspend fun delete(device: DeviceEntity)
    
    /**
     * 根据UUID删除设备
     */
    @Query("DELETE FROM devices WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
