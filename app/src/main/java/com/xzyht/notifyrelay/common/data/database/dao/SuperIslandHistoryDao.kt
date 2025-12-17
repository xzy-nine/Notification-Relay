package com.xzyht.notifyrelay.common.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity

/**
 * 超级岛历史记录DAO
 * 定义超级岛历史记录的数据库操作
 */
@Dao
interface SuperIslandHistoryDao {
    /**
     * 获取所有超级岛历史记录
     */
    @Query("SELECT * FROM super_island_history ORDER BY id DESC")
    suspend fun getAllHistory(): List<SuperIslandHistoryEntity>
    
    /**
     * 插入超级岛历史记录（冲突时替换）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(history: List<SuperIslandHistoryEntity>)
    
    /**
     * 插入单条超级岛历史记录（冲突时替换）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SuperIslandHistoryEntity)
    
    /**
     * 清空所有超级岛历史记录
     */
    @Query("DELETE FROM super_island_history")
    suspend fun clearAll()
    
    /**
     * 获取最新的N条超级岛历史记录
     */
    @Query("SELECT * FROM super_island_history ORDER BY id DESC LIMIT :limit")
    suspend fun getLatestHistory(limit: Int): List<SuperIslandHistoryEntity>
}
