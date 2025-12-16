package com.xzyht.notifyrelay.common.data.database.migration

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.common.data.PersistenceManager
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.common.data.database.entity.AppConfigEntity
import com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity
import com.xzyht.notifyrelay.common.data.database.entity.NotificationRecordEntity
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecord
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecordEntity as OldNotificationRecordEntity
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import java.io.File

/**
 * 迁移帮助类
 * 实现从旧存储到Room数据库的迁移逻辑
 */
object MigrationHelper {
    private val gson = Gson()
    
    /**
     * 迁移应用配置
     */
    suspend fun migrateAppConfig(context: Context, appConfigDao: com.xzyht.notifyrelay.common.data.database.dao.AppConfigDao) {
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "开始迁移应用配置")
        }
        
        val configs = mutableListOf<AppConfigEntity>()
        
        // 迁移逻辑已经简化，因为StorageManager现在直接使用Room数据库
        // 我们只需要确保迁移标记被正确设置
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "应用配置迁移已简化，因为StorageManager现在直接使用Room数据库")
        }
        
        // 插入到数据库
        if (configs.isNotEmpty()) {
            appConfigDao.insertAll(configs)
            if (BuildConfig.DEBUG) {
                Log.d("MigrationHelper", "迁移应用配置完成，共${configs.size}条")
            }
        }
    }
    
    /**
     * 迁移设备信息
     */
    suspend fun migrateDevices(context: Context, deviceDao: com.xzyht.notifyrelay.common.data.database.dao.DeviceDao) {
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "开始迁移设备信息")
        }
        
        // 从SharedPreferences读取设备数据
        val devicesJson = StorageManager.getString(context, "authed_devices", "[]")
        val devicesArray = JSONArray(devicesJson)
        
        val deviceEntities = mutableListOf<DeviceEntity>()
        for (i in 0 until devicesArray.length()) {
            try {
                val deviceObj = devicesArray.getJSONObject(i)
                deviceEntities.add(
                    DeviceEntity(
                        uuid = deviceObj.getString("uuid"),
                        publicKey = deviceObj.getString("publicKey"),
                        sharedSecret = deviceObj.getString("sharedSecret"),
                        isAccepted = deviceObj.getBoolean("isAccepted"),
                        displayName = deviceObj.getString("displayName"),
                        lastIp = deviceObj.getString("lastIp"),
                        lastPort = deviceObj.getInt("lastPort")
                    )
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("MigrationHelper", "迁移设备信息失败: ${e.message}", e)
                }
            }
        }
        
        // 插入到数据库
        if (deviceEntities.isNotEmpty()) {
            deviceDao.insertAll(deviceEntities)
            if (BuildConfig.DEBUG) {
                Log.d("MigrationHelper", "迁移设备信息完成，共${deviceEntities.size}条")
            }
        }
    }
    
    /**
     * 迁移通知记录
     */
    suspend fun migrateNotifications(
        context: Context,
        notificationRecordDao: com.xzyht.notifyrelay.common.data.database.dao.NotificationRecordDao,
        deviceDao: com.xzyht.notifyrelay.common.data.database.dao.DeviceDao
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "开始迁移通知记录")
        }
        
        // 获取所有通知文件
        val files = PersistenceManager.getAllNotificationFiles(context)
        
        // 添加本地设备（如果不存在）
        val localDeviceUuid = "本机"
        val localDevice = deviceDao.getByUuid(localDeviceUuid)
        if (localDevice == null) {
            deviceDao.insert(
                DeviceEntity(
                    uuid = localDeviceUuid,
                    publicKey = "",
                    sharedSecret = "",
                    isAccepted = true,
                    displayName = "本机",
                    lastIp = "localhost",
                    lastPort = 0
                )
            )
        }
        
        val notificationEntities = mutableListOf<NotificationRecordEntity>()
        
        for (file in files) {
            try {
                // 解析文件名，获取设备ID
                val fileName = file.name
                val deviceId = fileName.removePrefix("notification_records_").removeSuffix(".json")
                
                // 读取文件内容
                val jsonContent = file.readText()
                val typeToken = object : TypeToken<List<OldNotificationRecordEntity>>() {}
                val oldRecords = gson.fromJson<List<OldNotificationRecordEntity>>(jsonContent, typeToken.type)
                
                // 转换为Room实体
                for (oldRecord in oldRecords) {
                    val deviceUuid = if (deviceId == "local") localDeviceUuid else deviceId
                    
                    // 确保设备存在
                    val device = deviceDao.getByUuid(deviceUuid)
                    if (device == null) {
                        // 创建未知设备
                        deviceDao.insert(
                            DeviceEntity(
                                uuid = deviceUuid,
                                publicKey = "",
                                sharedSecret = "",
                                isAccepted = false,
                                displayName = "未知设备($deviceId)",
                                lastIp = "",
                                lastPort = 0
                            )
                        )
                    }
                    
                    // 转换为新实体
                    notificationEntities.add(
                        NotificationRecordEntity(
                            key = oldRecord.key,
                            deviceUuid = deviceUuid,
                            packageName = oldRecord.packageName,
                            appName = oldRecord.appName,
                            title = oldRecord.title,
                            text = oldRecord.text,
                            time = oldRecord.time
                        )
                    )
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d("MigrationHelper", "迁移文件 $fileName 完成，共${oldRecords.size}条通知")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("MigrationHelper", "迁移文件 ${file.name} 失败: ${e.message}", e)
                }
            }
        }
        
        // 插入到数据库
        if (notificationEntities.isNotEmpty()) {
            // 分批插入，避免内存占用过大
            val batchSize = 100
            for (i in notificationEntities.indices step batchSize) {
                val endIndex = minOf(i + batchSize, notificationEntities.size)
                val batch = notificationEntities.subList(i, endIndex)
                notificationRecordDao.insertAll(batch)
            }
            
            if (BuildConfig.DEBUG) {
                Log.d("MigrationHelper", "迁移通知记录完成，共${notificationEntities.size}条")
            }
        }
    }
    
    /**
     * 迁移超级岛历史记录
     */
    suspend fun migrateSuperIslandHistory(
        context: Context,
        superIslandHistoryDao: com.xzyht.notifyrelay.common.data.database.dao.SuperIslandHistoryDao
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "开始迁移超级岛历史记录")
        }
        
        try {
            // 从旧存储读取超级岛历史记录
            val oldHistoryTypeToken = object : TypeToken<List<com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandHistoryEntry>>() {}
            val oldHistory = PersistenceManager.readNotificationRecords(
                context,
                "super_island_history",
                oldHistoryTypeToken
            )
            
            // 转换为Room实体
            val entities = oldHistory.map { oldEntry ->
                com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity(
                    id = oldEntry.id,
                    sourceDeviceUuid = oldEntry.sourceDeviceUuid,
                    originalPackage = oldEntry.originalPackage,
                    mappedPackage = oldEntry.mappedPackage,
                    appName = oldEntry.appName,
                    title = oldEntry.title,
                    text = oldEntry.text,
                    paramV2Raw = oldEntry.paramV2Raw,
                    picMap = gson.toJson(oldEntry.picMap),
                    rawPayload = oldEntry.rawPayload
                )
            }
            
            // 插入到数据库
            if (entities.isNotEmpty()) {
                superIslandHistoryDao.insertAll(entities)
                if (BuildConfig.DEBUG) {
                    Log.d("MigrationHelper", "迁移超级岛历史记录完成，共${entities.size}条")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e("MigrationHelper", "迁移超级岛历史记录失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 清理旧的存储文件
     */
    fun cleanupLegacyStorage(context: Context) {
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "开始清理旧存储文件")
        }
        
        // 删除通知记录JSON文件
        val files = PersistenceManager.getAllNotificationFiles(context)
        for (file in files) {
            if (file.delete()) {
                if (BuildConfig.DEBUG) {
                    Log.d("MigrationHelper", "删除旧通知文件 ${file.name}")
                }
            }
        }
        
        // 标记迁移完成
        StorageManager.putBoolean(context, "migration_completed", true)
        
        if (BuildConfig.DEBUG) {
            Log.d("MigrationHelper", "清理旧存储文件完成")
        }
    }
    
    /**
     * 检查是否需要迁移
     */
    fun shouldMigrate(context: Context): Boolean {
        return !StorageManager.getBoolean(context, "migration_completed", false)
    }
}
