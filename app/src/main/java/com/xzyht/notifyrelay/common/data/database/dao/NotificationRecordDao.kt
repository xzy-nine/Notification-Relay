package com.xzyht.notifyrelay.common.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xzyht.notifyrelay.common.data.database.entity.NotificationRecordEntity

/**
 * 通知记录DAO接口
 * 定义通知记录的增删改查操作
 */
@Dao
interface NotificationRecordDao {
    /**
     * 根据设备UUID获取通知记录
     */
    @Query("SELECT * FROM notification_records WHERE deviceUuid = :deviceUuid ORDER BY time DESC")
    suspend fun getByDevice(deviceUuid: String): List<NotificationRecordEntity>
    
    /**
     * 获取所有通知记录
     */
    @Query("SELECT * FROM notification_records ORDER BY time DESC")
    suspend fun getAll(): List<NotificationRecordEntity>
    
    /**
     * 根据key获取通知记录
     */
    @Query("SELECT * FROM notification_records WHERE key = :key")
    suspend fun getByKey(key: String): NotificationRecordEntity?
    
    /**
     * 插入或更新通知记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NotificationRecordEntity)
    
    /**
     * 批量插入或更新通知记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<NotificationRecordEntity>)
    
    /**
     * 删除通知记录
     */
    @Delete
    suspend fun delete(record: NotificationRecordEntity)
    
    /**
     * 根据key删除通知记录
     */
    @Query("DELETE FROM notification_records WHERE key = :key")
    suspend fun deleteByKey(key: String)
    
    /**
     * 根据设备UUID删除所有通知记录
     */
    @Query("DELETE FROM notification_records WHERE deviceUuid = :deviceUuid")
    suspend fun deleteByDevice(deviceUuid: String)
    
    /**
     * 删除指定时间之前的通知记录
     */
    @Query("DELETE FROM notification_records WHERE time < :timeThreshold")
    suspend fun deleteOldRecords(timeThreshold: Long)
    
    /**
     * 获取设备的通知记录数量
     */
    @Query("SELECT COUNT(*) FROM notification_records WHERE deviceUuid = :deviceUuid")
    suspend fun countByDevice(deviceUuid: String): Int
}
