package com.xzyht.notifyrelay.common.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xzyht.notifyrelay.common.data.database.entity.AppConfigEntity

/**
 * 应用配置DAO接口
 * 定义应用配置的增删改查操作
 */
@Dao
interface AppConfigDao {
    /**
     * 获取所有配置
     */
    @Query("SELECT * FROM app_config")
    suspend fun getAll(): List<AppConfigEntity>
    
    /**
     * 根据key获取配置值
     */
    @Query("SELECT value FROM app_config WHERE key = :key")
    suspend fun getValue(key: String): String?
    
    /**
     * 插入或更新配置
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: AppConfigEntity)
    
    /**
     * 批量插入或更新配置
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<AppConfigEntity>)
    
    /**
     * 删除配置
     */
    @Delete
    suspend fun delete(config: AppConfigEntity)
    
    /**
     * 根据key删除配置
     */
    @Query("DELETE FROM app_config WHERE key = :key")
    suspend fun deleteByKey(key: String)
}
