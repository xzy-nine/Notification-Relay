package notifyrelay.data.database.migration

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import notifyrelay.base.util.Logger
import notifyrelay.data.PersistenceManager
import notifyrelay.data.StorageManager
import notifyrelay.data.database.dao.AppConfigDao
import notifyrelay.data.database.dao.AppDao
import notifyrelay.data.database.dao.AppDeviceDao
import notifyrelay.data.database.dao.NotificationRecordDao
import notifyrelay.data.database.dao.SuperIslandHistoryDao
import notifyrelay.data.database.entity.AppConfigEntity
import notifyrelay.data.database.entity.AppDeviceEntity
import notifyrelay.data.database.entity.AppEntity
import notifyrelay.data.database.entity.NotificationRecordEntity
import notifyrelay.data.database.entity.SuperIslandHistoryEntity
import java.io.ByteArrayOutputStream

/**
 * 迁移帮助类
 * 实现从旧存储到Room数据库的迁移逻辑
 */
object MigrationHelper {
    private val gson = Gson()

    /**
     * 迁移应用配置
     */
    suspend fun migrateAppConfig(
        context: Context,
        appConfigDao: AppConfigDao,
    ) {
        run {
            // Logger.d("MigrationHelper", "开始迁移应用配置")
        }

        val configs = mutableListOf<AppConfigEntity>()

        // 迁移逻辑已经简化，因为StorageManager现在直接使用Room数据库
        // 我们只需要确保迁移标记被正确设置
        // Logger.d("MigrationHelper", "应用配置迁移已简化，因为StorageManager现在直接使用Room数据库")

        // 插入到数据库
        if (configs.isNotEmpty()) {
            appConfigDao.insertAll(configs)
            // Logger.d("MigrationHelper", "迁移应用配置完成，共${configs.size}条")
        }
    }

    /**
     * 迁移超级岛历史记录
     */
    suspend fun migrateNotifications(
        context: Context,
        notificationRecordDao: NotificationRecordDao,
    ) {
        // Logger.d("MigrationHelper", "开始迁移通知记录")

        // 获取所有通知文件
        val files = PersistenceManager.getAllNotificationFiles(context)

        val notificationEntities = mutableListOf<NotificationRecordEntity>()

        for (file in files) {
            try {
                // 解析文件名，获取设备ID
                val fileName = file.name
                val deviceId = fileName.removePrefix("notification_records_").removeSuffix(".json")

                // 读取文件内容
                val jsonContent = file.readText()
                val typeToken = object : TypeToken<List<Map<String, Any>>>() {}
                val oldRecords = gson.fromJson<List<Map<String, Any>>>(jsonContent, typeToken.type)

                // 转换为Room实体
                for (oldRecord in oldRecords) {
                    val deviceUuid = if (deviceId == "local") "本机" else deviceId

                    // 转换为新实体
                    notificationEntities.add(
                        NotificationRecordEntity(
                            key = oldRecord["key"] as? String ?: "",
                            deviceUuid = deviceUuid,
                            packageName = oldRecord["packageName"] as? String ?: "",
                            appName = oldRecord["appName"] as? String,
                            title = oldRecord["title"] as? String,
                            text = oldRecord["text"] as? String,
                            time = (oldRecord["time"] as? Number)?.toLong() ?: 0,
                        ),
                    )
                }

                {
                    // Logger.d("MigrationHelper", "迁移文件 $fileName 完成，共${oldRecords.size}条通知")
                }
            } catch (e: Exception) {
                {
                    Logger.e("MigrationHelper", "迁移文件 ${file.name} 失败: ${e.message}", e)
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

            {
                // Logger.d("MigrationHelper", "迁移通知记录完成，共${notificationEntities.size}条")
            }
        }
    }

    /**
     * 迁移超级岛历史记录
     */
    suspend fun migrateSuperIslandHistory(
        context: Context,
        superIslandHistoryDao: SuperIslandHistoryDao,
    ) {
        {
            // Logger.d("MigrationHelper", "开始迁移超级岛历史记录")
        }

        try {
            // 从旧存储读取超级岛历史记录
            val oldHistoryTypeToken = object : TypeToken<List<Map<String, Any>>>() {}
            val oldHistory =
                PersistenceManager.readNotificationRecords(
                    context,
                    "super_island_history",
                    oldHistoryTypeToken,
                )

            // 转换为Room实体
            val entities =
                oldHistory.mapNotNull { oldEntry ->
                    try {
                        SuperIslandHistoryEntity(
                            id = (oldEntry["id"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                            sourceDeviceUuid = oldEntry["sourceDeviceUuid"] as? String ?: "",
                            originalPackage = oldEntry["originalPackage"] as? String ?: "",
                            mappedPackage = oldEntry["mappedPackage"] as? String ?: "",
                            appName = oldEntry["appName"] as? String,
                            title = oldEntry["title"] as? String,
                            text = oldEntry["text"] as? String,
                            paramV2Raw = oldEntry["paramV2Raw"] as? String,
                            picMap = oldEntry["picMap"]?.let { gson.toJson(it) } ?: "{}",
                            rawPayload = oldEntry["rawPayload"] as? String,
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

            // 插入到数据库
            if (entities.isNotEmpty()) {
                superIslandHistoryDao.insertAll(entities)
                // Logger.d("MigrationHelper", "迁移超级岛历史记录完成，共${entities.size}条")
            }
        } catch (e: Exception) {
            Logger.e("MigrationHelper", "迁移超级岛历史记录失败: ${e.message}", e)
        }
    }

    /**
     * 迁移应用信息和图标
     */
    suspend fun migrateApps(
        context: Context,
        appDao: AppDao,
        appDeviceDao: AppDeviceDao,
    ) {
        // Logger.d("MigrationHelper", "开始迁移应用信息和图标")

        try {
            // 迁移本地应用
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(0)

            val appEntities = mutableListOf<AppEntity>()
            val appDeviceEntities = mutableListOf<AppDeviceEntity>()

            installedApps.forEach { appInfo ->
                try {
                    val packageName = appInfo.packageName
                    val appName =
                        try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            packageName
                        }
                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    // 获取应用图标
                    var iconBytes: ByteArray? = null
                    try {
                        val bitmap =
                            when (val drawable = pm.getApplicationIcon(appInfo)) {
                                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                                else -> {
                                    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                                    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                                    val createdBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(createdBitmap)
                                    drawable.setBounds(0, 0, width, height)
                                    drawable.draw(canvas)
                                    createdBitmap
                                }
                            }
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        iconBytes = baos.toByteArray()
                    } catch (e: Exception) {
                        Logger.w("MigrationHelper", "获取应用图标失败: ${appInfo.packageName}", e)
                    }

                    val appEntity =
                        AppEntity(
                            packageName = packageName,
                            appName = appName,
                            isSystemApp = isSystemApp,
                            iconBytes = iconBytes,
                            isIconMissing = iconBytes == null,
                            lastUpdated = System.currentTimeMillis(),
                        )
                    appEntities.add(appEntity)

                    val appDeviceEntity =
                        AppDeviceEntity(
                            packageName = packageName,
                            sourceDevice = "local",
                            lastUpdated = System.currentTimeMillis(),
                        )
                    appDeviceEntities.add(appDeviceEntity)
                } catch (e: Exception) {
                    Logger.w("MigrationHelper", "迁移应用信息失败: ${appInfo.packageName}", e)
                }
            }

            // 批量插入到数据库
            if (appEntities.isNotEmpty()) {
                appDao.insertAll(appEntities)
                Logger.d("MigrationHelper", "迁移应用信息完成，共${appEntities.size}条")
            }

            if (appDeviceEntities.isNotEmpty()) {
                appDeviceDao.insertAll(appDeviceEntities)
                Logger.d("MigrationHelper", "迁移应用设备关联完成，共${appDeviceEntities.size}条")
            }
        } catch (e: Exception) {
            Logger.e("MigrationHelper", "迁移应用信息和图标失败: ${e.message}", e)
        }
    }

    /**
     * 清理旧的存储文件
     */
    fun cleanupLegacyStorage(context: Context) {
        // Logger.d("MigrationHelper", "开始清理旧存储文件")

        // 删除通知记录JSON文件
        val files = PersistenceManager.getAllNotificationFiles(context)
        for (file in files) {
            if (file.delete()) {
                {
                    // Logger.d("MigrationHelper", "删除旧通知文件 ${file.name}")
                }
            }
        }

        // 标记迁移完成
        StorageManager.putBoolean(context, "migration_completed", true)
        // Logger.d("MigrationHelper", "清理旧存储文件完成")
    }

    /**
     * 检查是否需要迁移
     */
    fun shouldMigrate(context: Context): Boolean = !StorageManager.getBoolean(context, "migration_completed", false)
}
