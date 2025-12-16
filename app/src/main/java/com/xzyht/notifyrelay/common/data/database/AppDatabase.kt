package com.xzyht.notifyrelay.common.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xzyht.notifyrelay.common.data.database.dao.AppConfigDao
import com.xzyht.notifyrelay.common.data.database.dao.DeviceDao
import com.xzyht.notifyrelay.common.data.database.dao.NotificationRecordDao
import com.xzyht.notifyrelay.common.data.database.dao.SuperIslandHistoryDao
import com.xzyht.notifyrelay.common.data.database.entity.AppConfigEntity
import com.xzyht.notifyrelay.common.data.database.entity.DeviceEntity
import com.xzyht.notifyrelay.common.data.database.entity.NotificationRecordEntity
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Room数据库核心类
 * 定义数据库的版本、实体类和DAO接口
 */
@Database(
    entities = [
        AppConfigEntity::class,
        DeviceEntity::class,
        NotificationRecordEntity::class,
        SuperIslandHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    // DAO接口
    abstract fun appConfigDao(): AppConfigDao
    abstract fun deviceDao(): DeviceDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun superIslandHistoryDao(): SuperIslandHistoryDao
    
    companion object {
        // 数据库名称
        private const val DATABASE_NAME = "notify_relay.db"
        
        // 单例实例
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * 获取数据库实例（单例模式）
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // 数据库创建后执行迁移逻辑
                        INSTANCE?.let {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                // 执行迁移逻辑
                                migrateFromLegacyStorage(context, it)
                            }
                        }
                    }
                })
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
        }
        
        /**
         * 数据库迁移：从版本1到版本2
         * 删除notification_records表中的外键约束
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. 创建新表，没有外键约束
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_records_new (
                        key TEXT PRIMARY KEY NOT NULL,
                        deviceUuid TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT,
                        title TEXT,
                        text TEXT,
                        time INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                
                // 2. 创建索引
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_deviceUuid ON notification_records_new(deviceUuid)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_time ON notification_records_new(time)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_packageName ON notification_records_new(packageName)")
                
                // 3. 复制数据
                database.execSQL("""
                    INSERT INTO notification_records_new (key, deviceUuid, packageName, appName, title, text, time, createdAt)
                    SELECT key, deviceUuid, packageName, appName, title, text, time, createdAt
                    FROM notification_records
                """)
                
                // 4. 删除旧表
                database.execSQL("DROP TABLE notification_records")
                
                // 5. 重命名新表
                database.execSQL("ALTER TABLE notification_records_new RENAME TO notification_records")
            }
        }
        
        /**
         * 从旧存储迁移数据到Room数据库
         */
        private suspend fun migrateFromLegacyStorage(context: Context, database: AppDatabase) {
            // 检查是否需要迁移
            if (!com.xzyht.notifyrelay.common.data.database.migration.MigrationHelper.shouldMigrate(context)) {
                return
            }
            
            try {
                // 迁移应用配置
                com.xzyht.notifyrelay.common.data.database.migration.MigrationHelper.migrateAppConfig(
                    context,
                    database.appConfigDao()
                )
                
                // 迁移设备信息
                com.xzyht.notifyrelay.common.data.database.migration.MigrationHelper.migrateDevices(
                    context,
                    database.deviceDao()
                )
                
                // 迁移通知记录
                com.xzyht.notifyrelay.common.data.database.migration.MigrationHelper.migrateNotifications(
                    context,
                    database.notificationRecordDao(),
                    database.deviceDao()
                )
                
                // 清理旧存储文件
                com.xzyht.notifyrelay.common.data.database.migration.MigrationHelper.cleanupLegacyStorage(context)
            } catch (e: Exception) {
                if (com.xzyht.notifyrelay.BuildConfig.DEBUG) {
                    android.util.Log.e("AppDatabase", "迁移失败: ${e.message}", e)
                }
            }
        }
    }
}
