@file:Suppress(
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
)

package notifyrelay.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import notifyrelay.base.util.Logger
import notifyrelay.data.FilterConfigDefaults
import notifyrelay.data.database.dao.AppConfigDao
import notifyrelay.data.database.dao.AppDao
import notifyrelay.data.database.dao.AppDeviceDao
import notifyrelay.data.database.dao.FilterListDao
import notifyrelay.data.database.dao.NotificationRecordDao
import notifyrelay.data.database.dao.SuperIslandHistoryDao
import notifyrelay.data.database.dao.SuperIslandImageDao
import notifyrelay.data.database.dao.SuperIslandMirrorFilterDao
import notifyrelay.data.database.entity.AppConfigEntity
import notifyrelay.data.database.entity.AppDeviceEntity
import notifyrelay.data.database.entity.AppEntity
import notifyrelay.data.database.entity.BlackListEntryEntity
import notifyrelay.data.database.entity.FilterEntryEntity
import notifyrelay.data.database.entity.NotificationRecordEntity
import notifyrelay.data.database.entity.PackageGroupEntity
import notifyrelay.data.database.entity.PackageGroupItemEntity
import notifyrelay.data.database.entity.SuperIslandHistoryEntity
import notifyrelay.data.database.entity.SuperIslandImageBindingEntity
import notifyrelay.data.database.entity.SuperIslandImageEntity
import notifyrelay.data.database.entity.SuperIslandMirrorFilterEntity
import notifyrelay.data.database.entity.WhiteListEntryEntity
import notifyrelay.data.database.migration.MigrationHelper

/**
 * Room数据库核心类
 * 定义数据库的版本、实体类和DAO接口
 *
 * 版本历史：
 * v1: 初始版本
 * v2: 删除notification_records表外键约束
 * v3: super_island_history表新增featureId字段
 * v4: 新增apps表和app_devices表
 * v5: notification_records表新增复合索引
 * v6: 新增超级岛图片去重表与绑定表
 * v7: 新增super_island_mirror_filters表
 * v8: 名单类配置从app_config移出为独立表（black_list_entries、white_list_entries、filter_entries、package_groups、package_group_items），镜像过滤默认包名入库
 */
@Suppress(
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
    "SpellCheckingInspection",
)
@Database(
    entities = [
        AppConfigEntity::class,
        AppEntity::class,
        AppDeviceEntity::class,
        NotificationRecordEntity::class,
        SuperIslandHistoryEntity::class,
        SuperIslandImageEntity::class,
        SuperIslandImageBindingEntity::class,
        SuperIslandMirrorFilterEntity::class,
        BlackListEntryEntity::class,
        WhiteListEntryEntity::class,
        FilterEntryEntity::class,
        PackageGroupEntity::class,
        PackageGroupItemEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    // DAO接口
    abstract fun appConfigDao(): AppConfigDao

    abstract fun appDao(): AppDao

    abstract fun appDeviceDao(): AppDeviceDao

    abstract fun notificationRecordDao(): NotificationRecordDao

    abstract fun superIslandHistoryDao(): SuperIslandHistoryDao

    abstract fun superIslandImageDao(): SuperIslandImageDao

    abstract fun superIslandMirrorFilterDao(): SuperIslandMirrorFilterDao

    abstract fun filterListDao(): FilterListDao

    companion object {
        // 数据库名称
        private const val DATABASE_NAME = "notify_relay.db"

        // 单例实例
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 获取数据库实例（单例模式）
         */
        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DATABASE_NAME,
                    ).addCallback(
                        object : Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                // 数据库创建后执行迁移逻辑
                                instance?.let {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        // 执行迁移逻辑
                                        migrateFromLegacyStorage(context, it)
                                    }
                                }
                            }
                        },
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                    .also { instance = it }
            }

        /**
         * 数据库迁移：从版本1到版本2
         * 删除notification_records表中的外键约束
         */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 1. 创建新表，没有外键约束
                    database.execSQL(
                        """
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
                """,
                    )

                    // 2. 创建索引
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_deviceUuid ON notification_records_new(deviceUuid)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_time ON notification_records_new(time)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_records_new_packageName ON notification_records_new(packageName)")

                    // 3. 复制数据
                    database.execSQL(
                        """
                    INSERT INTO notification_records_new (key, deviceUuid, packageName, appName, title, text, time, createdAt)
                    SELECT key, deviceUuid, packageName, appName, title, text, time, createdAt
                    FROM notification_records
                """,
                    )

                    // 4. 删除旧表
                    database.execSQL("DROP TABLE notification_records")

                    // 5. 重命名新表
                    database.execSQL("ALTER TABLE notification_records_new RENAME TO notification_records")
                }
            }

        /**
         * 数据库迁移：从版本2到版本3
         * 为super_island_history表添加featureId字段和相关索引
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 1. 为super_island_history表添加featureId字段
                    database.execSQL("ALTER TABLE super_island_history ADD COLUMN featureId TEXT")

                    // 2. 创建索引
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_super_island_feature_id ON super_island_history(featureId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_super_island_device_feature ON super_island_history(sourceDeviceUuid, featureId)")
                }
            }

        /**
         * 数据库迁移：从版本3到版本4
         * 添加apps表和app_devices表
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 1. 创建apps表
                    database.execSQL(
                        """
                    CREATE TABLE IF NOT EXISTS apps (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        appName TEXT NOT NULL,
                        isSystemApp INTEGER NOT NULL,
                        iconBytes BLOB,
                        isIconMissing INTEGER NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """,
                    )

                    // 2. 创建app_devices表
                    database.execSQL(
                        """
                    CREATE TABLE IF NOT EXISTS app_devices (
                        packageName TEXT NOT NULL,
                        sourceDevice TEXT NOT NULL,
                        lastUpdated INTEGER NOT NULL,
                        PRIMARY KEY (packageName, sourceDevice),
                        FOREIGN KEY (packageName) REFERENCES apps(packageName) ON DELETE CASCADE
                    )
                """,
                    )

                    // 3. 创建索引
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_app_devices_source_device ON app_devices(sourceDevice)")
                }
            }

        /**
         * 数据库迁移：从版本4到版本5
         * 为通知记录表添加复合索引
         */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_notification_records_device_package_time ON notification_records(deviceUuid, packageName, time)",
                    )
                }
            }

        /**
         * 数据库迁移：从版本5到版本6
         * 添加超级岛图片去重表与绑定表
         */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS super_island_images (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            contentHash TEXT NOT NULL,
                            data TEXT NOT NULL,
                            lastUpdated INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_super_island_images_contentHash ON super_island_images(contentHash)",
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS super_island_image_bindings (
                            packageName TEXT NOT NULL,
                            imageKey TEXT NOT NULL,
                            imageId INTEGER NOT NULL,
                            lastUpdated INTEGER NOT NULL,
                            PRIMARY KEY (packageName, imageKey),
                            FOREIGN KEY (imageId) REFERENCES super_island_images(id) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_super_island_image_bindings_imageId ON super_island_image_bindings(imageId)",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_super_island_image_bindings_packageName ON super_island_image_bindings(packageName)",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_super_island_image_bindings_imageKey ON super_island_image_bindings(imageKey)",
                    )
                }
            }

        /**
         * 数据库迁移：从版本6到版本7
         * 添加超级岛镜像过滤包名表
         */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS super_island_mirror_filters (
                            packageName TEXT PRIMARY KEY NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            lastUpdated INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

        /**
         * 数据库迁移：从版本7到版本8
         * 名单类配置从app_config移出为独立表：
         * - 远程黑名单/白名单（含启用状态）→ black_list_entries / white_list_entries
         * - 本地过滤条目（含启用状态）→ filter_entries
         * - 包名等价组（默认组+自定义组及启用状态）→ package_groups / package_group_items
         * - 超级岛镜像过滤默认包名禁用集 → super_island_mirror_filters 行
         */
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 1. 建表（与实体 schema 逐列一致）
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS black_list_entries (" +
                            "packageName TEXT NOT NULL, " +
                            "keyword TEXT NOT NULL DEFAULT '', " +
                            "enabled INTEGER NOT NULL DEFAULT 1, " +
                            "PRIMARY KEY (packageName, keyword))",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS white_list_entries (" +
                            "packageName TEXT NOT NULL, " +
                            "keyword TEXT NOT NULL DEFAULT '', " +
                            "enabled INTEGER NOT NULL DEFAULT 1, " +
                            "PRIMARY KEY (packageName, keyword))",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS filter_entries (" +
                            "keyword TEXT NOT NULL DEFAULT '', " +
                            "packageName TEXT NOT NULL DEFAULT '', " +
                            "enabled INTEGER NOT NULL DEFAULT 1, " +
                            "PRIMARY KEY (keyword, packageName))",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS package_groups (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "groupName TEXT NOT NULL, " +
                            "enabled INTEGER NOT NULL DEFAULT 1, " +
                            "isDefault INTEGER NOT NULL DEFAULT 0)",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS package_group_items (" +
                            "groupId INTEGER NOT NULL, " +
                            "packageName TEXT NOT NULL, " +
                            "PRIMARY KEY (groupId, packageName), " +
                            "FOREIGN KEY (groupId) REFERENCES package_groups(id) ON DELETE CASCADE)",
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_package_group_items_groupId ON package_group_items(groupId)")

                    // 2. 数据迁移（解析 app_config 旧 JSON 数据写入新表）
                    try {
                        migrateRemoteBlackWhiteList(database, "black_list_entries", "filter_black_list", "filter_black_list_enabled")
                        migrateRemoteBlackWhiteList(database, "white_list_entries", "filter_white_list", "filter_white_list_enabled")
                        migrateLegacySharedList(database)
                        migrateLocalFilterEntries(database)
                        migratePackageGroups(database)
                        migrateMirrorFilterDefaults(database)
                    } catch (e: Exception) {
                        // 抛出异常以让 Room 回滚整个迁移事务，避免版本被标记为 8 但表为空导致数据静默丢失；
                        // 下次启动会重新尝试迁移（旧数据仍保留在 app_config 中）
                        Logger.e("AppDatabase", "MIGRATION_7_8 名单数据迁移失败，将回滚迁移事务", e)
                        throw e
                    }

                    // 3. 清理已迁移的旧 key
                    val migratedKeys =
                        listOf(
                            "filter_black_list",
                            "filter_black_list_enabled",
                            "filter_white_list",
                            "filter_white_list_enabled",
                            "filter_filter_list",
                            "filter_filter_entries",
                            "filter_enabled_filter_entries",
                            "filter_package_groups",
                            "filter_default_group_enabled",
                            "filter_custom_group_enabled",
                            "general_super_island_mirror_filter_disabled_defaults",
                        )
                    migratedKeys.forEach { key ->
                        database.execSQL("DELETE FROM app_config WHERE key = ?", arrayOf<Any?>(key))
                    }
                }
            }

        // ---------- MIGRATION_8_9 辅助方法 ----------

        /**
         * MIGRATION_8_9：设备表退役（uuid/密钥移交 Rust 私有库）
         * - 将 devices 表数据转存到 device_migration 临时表（含 sharedSecret，
         *   供应用层一次性迁移到 Rust 后 DROP）
         * - 删除 devices 表（Room 后续不再管理该表）
         */
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS device_migration (" +
                            "uuid TEXT NOT NULL PRIMARY KEY, " +
                            "publicKey TEXT NOT NULL DEFAULT '', " +
                            "sharedSecret TEXT NOT NULL DEFAULT '', " +
                            "isAccepted INTEGER NOT NULL DEFAULT 0, " +
                            "displayName TEXT NOT NULL DEFAULT '', " +
                            "lastIp TEXT NOT NULL DEFAULT '', " +
                            "lastPort INTEGER NOT NULL DEFAULT 0, " +
                            "createdAt INTEGER NOT NULL DEFAULT 0, " +
                            "updatedAt INTEGER NOT NULL DEFAULT 0)",
                    )
                    database.execSQL(
                        "INSERT OR IGNORE INTO device_migration " +
                            "(uuid, publicKey, sharedSecret, isAccepted, displayName, lastIp, lastPort, createdAt, updatedAt) " +
                            "SELECT uuid, publicKey, sharedSecret, isAccepted, displayName, lastIp, lastPort, createdAt, updatedAt FROM devices",
                    )
                    database.execSQL("DROP TABLE IF EXISTS devices")
                }
            }

        // ---------- MIGRATION_7_8 辅助方法 ----------

        private fun readConfigValue(
            db: SupportSQLiteDatabase,
            key: String,
        ): String? {
            db.query("SELECT value FROM app_config WHERE key = ?", arrayOf<Any?>(key)).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

        private fun parseJsonSet(json: String?): Set<String> {
            if (json.isNullOrBlank()) return emptySet()
            return runCatching {
                val type = object : TypeToken<Set<String>>() {}.type
                @Suppress("UNCHECKED_CAST")
                (Gson().fromJson(json, type) as? Set<String>) ?: emptySet()
            }.getOrDefault(emptySet())
        }

        /**
         * 解析 JSON 数组字符串为有序 List。
         * 与 parseJsonSet 不同：保留原始顺序，专用于自定义包名组（依赖顺序保证命名一致性）。
         * Set 反序列化时顺序不可靠，会导致迁移后命名与 savePackageGroups 不一致。
         */
        private fun parseJsonList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val type = object : TypeToken<List<String>>() {}.type
                @Suppress("UNCHECKED_CAST")
                (Gson().fromJson(json, type) as? List<String>) ?: emptyList()
            }.getOrDefault(emptyList())
        }

        /** 黑白名单条目 "pkg|keyword" 或 "pkg" 解析为 (包名, 关键词) */
        private fun parseFilterEntry(serialized: String): Pair<String, String> {
            val arr = serialized.split("|", limit = 2)
            return arr[0] to (arr.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "")
        }

        private fun lastInsertRowId(db: SupportSQLiteDatabase): Long {
            db.query("SELECT last_insert_rowid()").use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }

        private fun migrateRemoteBlackWhiteList(
            db: SupportSQLiteDatabase,
            table: String,
            listKey: String,
            enabledKey: String,
        ) {
            val list = parseJsonSet(readConfigValue(db, listKey))
            if (list.isEmpty()) return
            val enabledValue = readConfigValue(db, enabledKey)
            val enabled = parseJsonSet(enabledValue)
            list.forEach { ser ->
                val (pkg, kw) = parseFilterEntry(ser)
                db.execSQL(
                    "INSERT INTO $table (packageName, keyword, enabled) VALUES (?, ?, ?)",
                    arrayOf<Any?>(pkg, kw, if (enabledValue.isNullOrBlank() || enabled.contains(ser)) 1 else 0),
                )
            }
        }

        /** 遗留共享 filter_filter_list：按当前 filter_mode 并入对应名单表 */
        private fun migrateLegacySharedList(db: SupportSQLiteDatabase) {
            val legacy = parseJsonSet(readConfigValue(db, "filter_filter_list"))
            if (legacy.isEmpty()) return
            val mode = readConfigValue(db, "filter_filter_mode") ?: "none"
            val table =
                when (mode) {
                    "black" -> "black_list_entries"
                    "white" -> "white_list_entries"
                    else -> return
                }
            legacy.forEach { ser ->
                val (pkg, kw) = parseFilterEntry(ser)
                db.execSQL(
                    "INSERT OR IGNORE INTO $table (packageName, keyword, enabled) VALUES (?, ?, 1)",
                    arrayOf<Any?>(pkg, kw),
                )
            }
        }

        private fun migrateLocalFilterEntries(db: SupportSQLiteDatabase) {
            val entries = parseJsonSet(readConfigValue(db, "filter_filter_entries"))
            if (entries.isEmpty()) return
            val enabledValue = readConfigValue(db, "filter_enabled_filter_entries")
            val enabled = parseJsonSet(enabledValue)
            entries.forEach { ser ->
                val parts = ser.split("\u001F", limit = 2)
                val kw = parts.getOrNull(0)?.replace("\\u001F", "\u001F") ?: ""
                val pkg = parts.getOrNull(1)?.replace("\\u001F", "\u001F") ?: ""
                db.execSQL(
                    "INSERT INTO filter_entries (keyword, packageName, enabled) VALUES (?, ?, ?)",
                    arrayOf<Any?>(kw, pkg, if (enabledValue.isNullOrBlank() || enabled.contains(ser)) 1 else 0),
                )
            }
        }

        /** 包名等价组：默认组（常量）+ 自定义组 → package_groups / package_group_items */
        private fun migratePackageGroups(db: SupportSQLiteDatabase) {
            // 默认组
            val defaultEnabled =
                (readConfigValue(db, "filter_default_group_enabled") ?: "1,1,1")
                    .split(",")
                    .map { it == "1" }
            FilterConfigDefaults.defaultPackageGroups.forEachIndexed { idx, pkgs ->
                val enabled = defaultEnabled.getOrNull(idx) ?: true
                db.execSQL(
                    "INSERT INTO package_groups (groupName, enabled, isDefault) VALUES (?, ?, 1)",
                    arrayOf<Any?>("默认组${idx + 1}", if (enabled) 1 else 0),
                )
                val groupId = lastInsertRowId(db)
                pkgs.forEach { pkg ->
                    db.execSQL(
                        "INSERT INTO package_group_items (groupId, packageName) VALUES (?, ?)",
                        arrayOf<Any?>(groupId, pkg),
                    )
                }
            }
            // 自定义组（使用 parseJsonList 保留 JSON 数组顺序，避免 Set 反序列化导致命名错位）
            val customRaw = parseJsonList(readConfigValue(db, "filter_package_groups"))
            val customEnabled =
                (readConfigValue(db, "filter_custom_group_enabled") ?: "")
                    .split(",")
                    .filter { it.isNotBlank() }
                    .map { it == "1" }
            customRaw.forEachIndexed { idx, groupStr ->
                val pkgs = groupStr.split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (pkgs.isEmpty()) return@forEachIndexed
                val enabled = customEnabled.getOrNull(idx) ?: true
                db.execSQL(
                    "INSERT INTO package_groups (groupName, enabled, isDefault) VALUES (?, ?, 0)",
                    arrayOf<Any?>("自定义组${idx + 1}", if (enabled) 1 else 0),
                )
                val groupId = lastInsertRowId(db)
                pkgs.forEach { pkg ->
                    db.execSQL(
                        "INSERT INTO package_group_items (groupId, packageName) VALUES (?, ?)",
                        arrayOf<Any?>(groupId, pkg),
                    )
                }
            }
        }

        /** 镜像过滤默认包名禁用集 → super_island_mirror_filters 行 */
        private fun migrateMirrorFilterDefaults(db: SupportSQLiteDatabase) {
            val disabled =
                (readConfigValue(db, "general_super_island_mirror_filter_disabled_defaults") ?: "")
                    .split(",")
                    .filter { it.isNotBlank() }
                    .toSet()
            val now = System.currentTimeMillis()
            FilterConfigDefaults.defaultMirrorPackages.forEach { pkg ->
                db.execSQL(
                    "INSERT OR IGNORE INTO super_island_mirror_filters (packageName, enabled, lastUpdated) VALUES (?, ?, ?)",
                    arrayOf<Any?>(pkg, if (disabled.contains(pkg)) 0 else 1, now),
                )
            }
        }

        /**
         * 从旧存储迁移数据到Room数据库
         */
        private suspend fun migrateFromLegacyStorage(
            context: Context,
            database: AppDatabase,
        ) {
            // 检查是否需要迁移
            if (!MigrationHelper.shouldMigrate(context)) {
                return
            }

            try {
                // 迁移应用配置
                MigrationHelper.migrateAppConfig(
                    context,
                    database.appConfigDao(),
                )

                // 迁移通知记录（设备表已退役，不再创建设备记录）
                MigrationHelper.migrateNotifications(
                    context,
                    database.notificationRecordDao(),
                )

                // 迁移应用信息和图标
                MigrationHelper.migrateApps(
                    context,
                    database.appDao(),
                    database.appDeviceDao(),
                )

                // 迁移超级岛历史记录
                MigrationHelper.migrateSuperIslandHistory(
                    context,
                    database.superIslandHistoryDao(),
                )

                // 清理旧存储文件
                MigrationHelper.cleanupLegacyStorage(context)
            } catch (e: Exception) {
                Logger.e("AppDatabase", "迁移失败: ${e.message}", e)
            }
        }
    }
}
