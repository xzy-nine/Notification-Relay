package com.xzyht.notifyrelay.common.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistorySummary

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
     * 获取所有超级岛历史记录的摘要（不包含 rawPayload），用于列表/摘要态展示，避免一次性载入大字段
     */
    @Query("SELECT id, sourceDeviceUuid, originalPackage, mappedPackage, appName, title, text, paramV2Raw, picMap, featureId FROM super_island_history ORDER BY id DESC")
    suspend fun getAllHistorySummary(): List<SuperIslandHistorySummary>
    
    /**
     * 根据特征ID获取最新的历史记录
     */
    @Query("SELECT * FROM super_island_history WHERE featureId = :featureId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestByFeatureId(featureId: String): SuperIslandHistoryEntity?
    
    /**
     * 获取每个特征ID对应的最新一条记录
     */
    @Query("SELECT * FROM super_island_history WHERE id IN (SELECT MAX(id) FROM super_island_history GROUP BY featureId) ORDER BY id DESC")
    suspend fun getLatestByDistinctFeatureId(): List<SuperIslandHistoryEntity>
    
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

    /**
     * 获取指定 id 的完整记录（包含 rawPayload），按需加载大字段
     */
    @Query("SELECT * FROM super_island_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SuperIslandHistoryEntity?

    /**
     * 仅按 id 获取 rawPayload 字段，便于按需加载大字符串
     */
    @Query("SELECT rawPayload FROM super_island_history WHERE id = :id LIMIT 1")
    suspend fun getRawPayloadById(id: Long): String?
    
    /**
     * 删除指定数量的旧记录，保留最新的记录
     */
    @Query("DELETE FROM super_island_history WHERE id NOT IN (SELECT id FROM super_island_history ORDER BY id DESC LIMIT :keepCount)")
    suspend fun deleteOldestRecords(keepCount: Int)
    
    /**
     * 删除单条记录
     */
    @Delete
    suspend fun delete(history: SuperIslandHistoryEntity)
}
