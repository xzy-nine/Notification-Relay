package com.xzyht.notifyrelay.common.data.database.repository

import android.content.Context
import com.xzyht.notifyrelay.common.data.database.AppDatabase
import com.xzyht.notifyrelay.common.data.database.entity.AppConfigEntity
import com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity
import com.xzyht.notifyrelay.common.data.database.entity.NotificationRecordEntity
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity

/**
 * 数据库仓库类
 * 封装Room数据库的访问逻辑
 */
class DatabaseRepository(private val database: AppDatabase) {
    // 应用配置相关
    private val appConfigDao = database.appConfigDao()
    // 设备相关
    private val deviceDao = database.deviceDao()
    // 通知记录相关
    val notificationRecordDao = database.notificationRecordDao()
    // 超级岛历史记录相关
    private val superIslandHistoryDao = database.superIslandHistoryDao()
    
    /**
     * 获取应用配置值
     */
    suspend fun getConfig(key: String, default: String = ""): String {
        return appConfigDao.getValue(key) ?: default
    }
    
    /**
     * 设置应用配置值
     */
    suspend fun setConfig(key: String, value: String) {
        appConfigDao.insert(AppConfigEntity(key, value))
    }
    
    /**
     * 获取所有设备
     */
    suspend fun getDevices(): List<DeviceEntity> {
        return deviceDao.getAll()
    }
    
    /**
     * 根据UUID获取设备
     */
    suspend fun getDeviceByUuid(uuid: String): DeviceEntity? {
        return deviceDao.getByUuid(uuid)
    }
    
    /**
     * 保存设备
     */
    suspend fun saveDevice(device: DeviceEntity) {
        deviceDao.insert(device)
    }
    
    /**
     * 删除设备
     */
    suspend fun deleteDevice(device: DeviceEntity) {
        deviceDao.delete(device)
    }
    
    /**
     * 根据UUID删除设备
     */
    suspend fun deleteDeviceByUuid(uuid: String) {
        deviceDao.deleteByUuid(uuid)
    }
    
    /**
     * 根据设备UUID获取通知记录
     */
    suspend fun getNotificationsByDevice(deviceUuid: String): List<NotificationRecordEntity> {
        return notificationRecordDao.getByDevice(deviceUuid)
    }
    
    /**
     * 获取所有通知记录
     */
    suspend fun getAllNotifications(): List<NotificationRecordEntity> {
        return notificationRecordDao.getAll()
    }
    
    /**
     * 根据key获取通知记录
     */
    suspend fun getNotificationByKey(key: String): NotificationRecordEntity? {
        return notificationRecordDao.getByKey(key)
    }
    
    /**
     * 保存通知记录
     */
    suspend fun saveNotification(record: NotificationRecordEntity) {
        notificationRecordDao.insert(record)
    }
    
    /**
     * 批量保存通知记录
     */
    suspend fun saveNotifications(records: List<NotificationRecordEntity>) {
        notificationRecordDao.insertAll(records)
    }
    
    /**
     * 删除通知记录
     */
    suspend fun deleteNotification(record: NotificationRecordEntity) {
        notificationRecordDao.delete(record)
    }
    
    /**
     * 根据key删除通知记录
     */
    suspend fun deleteNotificationByKey(key: String) {
        notificationRecordDao.deleteByKey(key)
    }
    
    /**
     * 删除设备的所有通知记录
     */
    suspend fun deleteNotificationsByDevice(deviceUuid: String) {
        notificationRecordDao.deleteByDevice(deviceUuid)
    }
    
    /**
     * 删除指定时间之前的通知记录
     */
    suspend fun deleteOldNotifications(timeThreshold: Long) {
        notificationRecordDao.deleteOldRecords(timeThreshold)
    }
    
    /**
     * 获取设备的通知记录数量
     */
    suspend fun getNotificationCountByDevice(deviceUuid: String): Int {
        return notificationRecordDao.countByDevice(deviceUuid)
    }
    
    // 超级岛历史记录相关方法
    
    /**
     * 获取所有超级岛历史记录（摘要）——不加载 rawPayload，避免占用大量内存
     * 包含所有记录，不进行去重，用于调试
     */
    suspend fun getSuperIslandHistory(): List<SuperIslandHistoryEntity> {
        // 使用只读取小字段的摘要查询，然后构造实体（rawPayload 设为 null）
        val summaries = superIslandHistoryDao.getAllHistorySummary()
        return summaries.map { s ->
            SuperIslandHistoryEntity(
                id = s.id,
                sourceDeviceUuid = s.sourceDeviceUuid,
                originalPackage = s.originalPackage,
                mappedPackage = s.mappedPackage,
                appName = s.appName,
                title = s.title,
                text = s.text,
                paramV2Raw = s.paramV2Raw,
                picMap = s.picMap,
                rawPayload = null,
                featureId = s.featureId
            )
        }
    }
    
    /**
     * 获取每个特征ID对应的最新一条超级岛历史记录
     * 用于去重显示，避免重复数据
     */
    suspend fun getLatestSuperIslandHistoryByFeature(): List<SuperIslandHistoryEntity> {
        // 直接使用数据库层面的去重查询
        return superIslandHistoryDao.getLatestByDistinctFeatureId()
    }
    
    // 删除不需要的isSameContent方法，因为现在使用数据库层面的去重
    
    /**
     * 根据特征ID获取最新的超级岛历史记录
     */
    suspend fun getLatestSuperIslandHistoryByFeatureId(featureId: String): SuperIslandHistoryEntity? {
        return superIslandHistoryDao.getLatestByFeatureId(featureId)
    }
    
    /**
     * 保存超级岛历史记录
     */
    suspend fun saveSuperIslandHistory(history: List<SuperIslandHistoryEntity>) {
        superIslandHistoryDao.insertAll(history)
    }
    
    /**
     * 保存单条超级岛历史记录
     */
    suspend fun saveSuperIslandHistory(history: SuperIslandHistoryEntity) {
        superIslandHistoryDao.insert(history)
    }
    
    /**
     * 清空所有超级岛历史记录
     */
    suspend fun clearSuperIslandHistory() {
        superIslandHistoryDao.clearAll()
    }

    /**
     * 按 id 获取完整的超级岛历史记录（包含 rawPayload），按需调用以避免一次性加载大字段
     */
    suspend fun getSuperIslandHistoryById(id: Long): SuperIslandHistoryEntity? {
        return superIslandHistoryDao.getById(id)
    }

    /**
     * 获取指定 id 的 rawPayload（仅字符串），按需使用以减少内存峰值
     */
    suspend fun getRawPayloadById(id: Long): String? {
        return superIslandHistoryDao.getRawPayloadById(id)
    }
    
    /**
     * 删除旧的超级岛历史记录，只保留最新的指定数量记录
     */
    suspend fun deleteOldSuperIslandHistory(keepCount: Int) {
        superIslandHistoryDao.deleteOldestRecords(keepCount)
    }
    
    /**
     * 删除单条超级岛历史记录
     */
    suspend fun deleteSuperIslandHistory(history: SuperIslandHistoryEntity) {
        superIslandHistoryDao.delete(history)
    }
    

    
    companion object {
        @Volatile
        private var INSTANCE: DatabaseRepository? = null
        
        /**
         * 获取数据库仓库实例（单例模式）
         */
        fun getInstance(context: Context): DatabaseRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseRepository(AppDatabase.getDatabase(context)).also { INSTANCE = it }
            }
        }
    }
}
