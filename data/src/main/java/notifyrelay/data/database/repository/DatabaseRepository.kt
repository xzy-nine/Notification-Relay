package notifyrelay.data.database.repository

import android.content.Context
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import notifyrelay.data.database.AppDatabase
import notifyrelay.data.database.dao.PackageCount
import notifyrelay.data.database.entity.AppConfigEntity
import notifyrelay.data.database.entity.AppDeviceEntity
import notifyrelay.data.database.entity.AppEntity
import notifyrelay.data.database.entity.BlackListEntryEntity
import notifyrelay.data.database.entity.FilterEntryEntity
import notifyrelay.data.database.entity.NotificationRecordEntity
import notifyrelay.data.database.entity.PackageGroupEntity
import notifyrelay.data.database.entity.PackageGroupItemEntity
import notifyrelay.data.database.entity.SuperIslandHistoryEntity
import notifyrelay.data.database.entity.SuperIslandHistorySummary
import notifyrelay.data.database.entity.SuperIslandImageBindingEntity
import notifyrelay.data.database.entity.SuperIslandImageEntity
import notifyrelay.data.database.entity.SuperIslandMirrorFilterEntity
import notifyrelay.data.database.entity.WhiteListEntryEntity

/**
 * 数据库仓库类
 * 封装Room数据库的访问逻辑
 */
class DatabaseRepository(
    private val database: AppDatabase,
) {
    // 应用配置相关
    private val appConfigDao = database.appConfigDao()

    // 应用相关
    private val appDao = database.appDao()

    // 应用设备关联相关
    private val appDeviceDao = database.appDeviceDao()

    // 通知记录相关
    val notificationRecordDao = database.notificationRecordDao()

    // 超级岛历史记录相关
    private val superIslandHistoryDao = database.superIslandHistoryDao()

    // 超级岛图片去重相关
    private val superIslandImageDao = database.superIslandImageDao()

    // 超级岛镜像过滤相关
    private val superIslandMirrorFilterDao = database.superIslandMirrorFilterDao()

    // 过滤名单相关
    private val filterListDao = database.filterListDao()

    /**
     * 获取应用配置值
     */
    suspend fun getConfig(
        key: String,
        default: String = "",
    ): String = appConfigDao.getValue(key) ?: default

    /**
     * 设置应用配置值
     */
    suspend fun setConfig(
        key: String,
        value: String,
    ) {
        appConfigDao.insert(AppConfigEntity(key, value))
    }

    /**
     * 根据设备UUID获取通知记录
     */
    suspend fun getNotificationsByDevice(deviceUuid: String): List<NotificationRecordEntity> = notificationRecordDao.getByDevice(deviceUuid)

    // ===== 设备表退役迁移（MIGRATION_8_9 → device_migration 临时表）=====

    /**
     * 读取旧 devices 表迁移出的设备行（应用层一次性迁移至 Rust 库后清理）
     * 幂等：表已被前述迁移 DROP 时返回空列表（二次启动不再报错）
     */
    suspend fun queryDeviceMigrationRows(): List<DeviceMigrationRow> =
        withContext(Dispatchers.IO) {
            val rows = mutableListOf<DeviceMigrationRow>()
            try {
                database.openHelper.readableDatabase.query(
                    "SELECT uuid, publicKey, sharedSecret, isAccepted, displayName, lastIp, lastPort FROM device_migration",
                    emptyArray<Any?>(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(
                            DeviceMigrationRow(
                                uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid")),
                                publicKey = cursor.getString(cursor.getColumnIndexOrThrow("publicKey")),
                                sharedSecret = cursor.getString(cursor.getColumnIndexOrThrow("sharedSecret")),
                                isAccepted = cursor.getInt(cursor.getColumnIndexOrThrow("isAccepted")) != 0,
                                displayName = cursor.getString(cursor.getColumnIndexOrThrow("displayName")),
                                lastIp = cursor.getString(cursor.getColumnIndexOrThrow("lastIp")),
                                lastPort = cursor.getInt(cursor.getColumnIndexOrThrow("lastPort")),
                            ),
                        )
                    }
                }
            } catch (e: android.database.sqlite.SQLiteException) {
                // 仅表不存在视为幂等（迁移已完成，返回空）；
                // 其他查询失败必须抛出，由调用方暂缓清理旧存储并在下次启动重试，
                // 否则迁移会被静默跳过导致密钥永久丢失
                if (e.message?.contains("no such table", ignoreCase = true) != true) {
                    throw e
                }
            }
            rows
        }

    /**
     * 删除指定 UUID 的设备迁移行（设备在迁移前被移除时）
     * 幂等：表已被前述迁移 DROP 时忽略（二次启动/删除设备不再崩溃）
     */
    suspend fun deleteDeviceMigrationByUuid(uuid: String) {
        withContext(Dispatchers.IO) {
            try {
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM device_migration WHERE uuid = ?",
                    arrayOf<Any?>(uuid),
                )
            } catch (e: android.database.sqlite.SQLiteException) {
                // 仅忽略 "no such table" 错误（表不存在：迁移已完成），其他错误重新抛出
                if (e.message?.contains("no such table", ignoreCase = true) != true) {
                    throw e
                }
            }
        }
    }

    /**
     * 迁移完成后丢弃临时表
     */
    suspend fun dropDeviceMigrationTable() {
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS device_migration")
        }
    }

    /**
     * 获取通知记录的分页数据源
     */
    fun getNotificationPagingSourceByDevice(deviceUuid: String): PagingSource<Int, NotificationRecordEntity> = notificationRecordDao.getPagingSourceByDevice(deviceUuid)

    /**
     * 获取设备的包名统计
     */
    suspend fun getPackageCountByDevice(deviceUuid: String): List<PackageCount> = notificationRecordDao.getPackageCountByDevice(deviceUuid)

    /**
     * 获取所有通知记录
     */
    suspend fun getAllNotifications(): List<NotificationRecordEntity> = notificationRecordDao.getAll()

    /**
     * 根据key获取通知记录
     */
    suspend fun getNotificationByKey(key: String): NotificationRecordEntity? = notificationRecordDao.getByKey(key)

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
    suspend fun getNotificationCountByDevice(deviceUuid: String): Int = notificationRecordDao.countByDevice(deviceUuid)

    /**
     * 根据包名和设备UUID删除通知记录
     */
    suspend fun deleteNotificationsByPackageAndDevice(
        packageName: String,
        deviceUuid: String,
    ) {
        notificationRecordDao.deleteByPackageAndDevice(packageName, deviceUuid)
    }

    /**
     * 根据包名和设备UUID获取通知记录，按时间降序排序
     */
    suspend fun getNotificationsByPackageAndDevice(
        packageName: String,
        deviceUuid: String,
    ): List<NotificationRecordEntity> = notificationRecordDao.getByPackageAndDevice(packageName, deviceUuid)

    /**
     * 根据包名和设备UUID删除最旧的通知记录，保留最新的指定数量
     */
    suspend fun deleteOldestNotificationsByPackageAndDevice(
        packageName: String,
        deviceUuid: String,
        keepCount: Int,
    ) {
        val totalCount = notificationRecordDao.countByPackageAndDevice(packageName, deviceUuid)
        if (totalCount > keepCount) {
            val deleteCount = totalCount - keepCount
            notificationRecordDao.deleteOldestByPackageAndDevice(packageName, deviceUuid, deleteCount)
        }
    }

    /**
     * 保存通知记录并限制每个包名的通知数量
     */
    suspend fun saveNotificationWithLimit(
        record: NotificationRecordEntity,
        maxCountPerPackage: Int = 80,
    ) {
        notificationRecordDao.insert(record)
        deleteOldestNotificationsByPackageAndDevice(record.packageName, record.deviceUuid, maxCountPerPackage)
    }

    /**
     * 批量保存通知记录并限制每个包名的通知数量
     */
    suspend fun saveNotificationsWithLimit(
        records: List<NotificationRecordEntity>,
        maxCountPerPackage: Int = 80,
    ) {
        notificationRecordDao.insertAll(records)
        // 对每个唯一的包名和设备组合进行限制
        val packageDevicePairs = records.groupBy { Pair(it.packageName, it.deviceUuid) }
        for ((pair, packageRecords) in packageDevicePairs) {
            val (packageName, deviceUuid) = pair
            deleteOldestNotificationsByPackageAndDevice(packageName, deviceUuid, maxCountPerPackage)
        }
    }

    // 超级岛历史记录相关方法

    /**
     * 获取所有超级岛历史记录（摘要）——不加载 rawPayload，避免占用大量内存
     * 包含所有记录，不进行去重，用于调试
     */
    suspend fun getSuperIslandHistory(): List<SuperIslandHistoryEntity> {
        val summaries = superIslandHistoryDao.getAllHistorySummary()
        return summaries.map { mapToSuperIslandHistoryEntity(it) }
    }

    private fun mapToSuperIslandHistoryEntity(s: SuperIslandHistorySummary): SuperIslandHistoryEntity =
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
            featureId = s.featureId,
        )

    /**
     * 获取完整的超级岛历史记录（包含 rawPayload），仅用于迁移与后台处理
     */
    suspend fun getSuperIslandHistoryFull(): List<SuperIslandHistoryEntity> = superIslandHistoryDao.getAllHistory()

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
    suspend fun getLatestSuperIslandHistoryByFeatureId(featureId: String): SuperIslandHistoryEntity? = superIslandHistoryDao.getLatestByFeatureId(featureId)

    /**
     * 保存超级岛历史记录列表
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
     * 根据特征ID和内容更新或插入超级岛历史记录
     * 相同特征ID但内容不同的记录会被保留
     */
    suspend fun upsertSuperIslandHistoryByFeatureAndContent(history: SuperIslandHistoryEntity) {
        superIslandHistoryDao.upsertByFeatureAndContent(history)
    }

    /**
     * 根据特征ID获取最新的超级岛历史记录
     */
    suspend fun getSuperIslandHistoryByFeatureId(featureId: String): List<SuperIslandHistoryEntity> = superIslandHistoryDao.getAllHistory().filter { it.featureId == featureId }

    /**
     * 清空所有超级岛历史记录
     */
    suspend fun clearSuperIslandHistory() {
        superIslandHistoryDao.clearAll()
    }

    /**
     * 按 id 获取完整的超级岛历史记录（包含 rawPayload），按需调用以避免一次性加载大字段
     */
    suspend fun getSuperIslandHistoryById(id: Long): SuperIslandHistoryEntity? = superIslandHistoryDao.getById(id)

    /**
     * 获取指定 id 的 rawPayload（仅字符串），按需使用以减少内存峰值
     */
    suspend fun getRawPayloadById(id: Long): String? = superIslandHistoryDao.getRawPayloadById(id)

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

    /**
     * 获取按包名分组的统计信息
     */
    suspend fun getSuperIslandPackageCount(): List<notifyrelay.data.database.dao.SuperIslandPackageCount> {
        val counts = superIslandHistoryDao.getPackageCount().toMutableList()
        val unknownCount = superIslandHistoryDao.getUnknownPackageCount()
        if (unknownCount != null && unknownCount.count > 0) {
            counts.add(unknownCount)
        }
        return counts.sortedByDescending { it.latestTime }
    }

    /**
     * 按包名获取所有历史记录摘要（用于分组内展示）
     */
    suspend fun getSuperIslandHistoryByPackage(packageName: String?): List<SuperIslandHistoryEntity> {
        val summaries = superIslandHistoryDao.getAllByPackage(packageName)
        return summaries.map { mapToSuperIslandHistoryEntity(it) }
    }

    /**
     * 按包名删除历史记录
     */
    suspend fun deleteSuperIslandHistoryByPackage(packageName: String?) {
        superIslandHistoryDao.deleteByPackage(packageName)
    }

    /**
     * 按ID删除单条历史记录
     */
    suspend fun deleteSuperIslandHistoryById(id: Long) {
        superIslandHistoryDao.deleteById(id)
    }

    /**
     * 获取超级岛历史记录总数
     */
    suspend fun getSuperIslandHistoryCount(): Int = superIslandHistoryDao.getCount()

    // 超级岛图片去重相关方法

    private suspend fun upsertOrReuseImage(
        contentHash: String,
        data: String,
        lastUpdated: Long,
    ): Long {
        val existingId = superIslandImageDao.getImageIdByHash(contentHash)
        return if (existingId != null) {
            superIslandImageDao.touchImage(existingId, lastUpdated)
            existingId
        } else {
            val insertedId =
                superIslandImageDao.insertImage(
                    SuperIslandImageEntity(
                        contentHash = contentHash,
                        data = data,
                        lastUpdated = lastUpdated,
                    ),
                )
            if (insertedId == -1L) {
                superIslandImageDao.getImageIdByHash(contentHash) ?: -1L
            } else {
                insertedId
            }
        }
    }

    /**
     * 插入或复用图片并更新绑定，返回图片ID
     */
    suspend fun upsertSuperIslandImageBinding(
        packageName: String,
        imageKey: String,
        contentHash: String,
        data: String,
        lastUpdated: Long,
    ): Long {
        val imageId = upsertOrReuseImage(contentHash, data, lastUpdated)

        if (imageId > 0) {
            superIslandImageDao.upsertBinding(
                SuperIslandImageBindingEntity(
                    packageName = packageName,
                    imageKey = imageKey,
                    imageId = imageId,
                    lastUpdated = lastUpdated,
                ),
            )
        }

        return imageId
    }

    /**
     * 插入或复用图片（无绑定）并返回图片ID
     */
    suspend fun upsertSuperIslandImage(
        contentHash: String,
        data: String,
        lastUpdated: Long,
    ): Long = upsertOrReuseImage(contentHash, data, lastUpdated)

    /**
     * 根据图片ID获取原始数据
     */
    suspend fun resolveSuperIslandImageById(imageId: Long): String? = superIslandImageDao.getImageDataById(imageId)

    /**
     * 根据包名与图片键获取原始数据
     */
    suspend fun resolveSuperIslandImageByBinding(
        packageName: String,
        imageKey: String,
    ): String? = superIslandImageDao.getImageDataByBinding(packageName, imageKey)

    /**
     * 清理超级岛图片：按时间与数量限制
     */
    suspend fun pruneSuperIslandImages(
        maxEntries: Int,
        maxAgeDays: Int,
    ) {
        val now = System.currentTimeMillis()
        if (maxAgeDays > 0) {
            val cutoff = now - maxAgeDays * 24L * 60L * 60L * 1000L
            superIslandImageDao.deleteImagesOlderThan(cutoff)
        }
        if (maxEntries > 0) {
            superIslandImageDao.deleteImagesKeepingLatest(maxEntries)
        }
    }

    /**
     * 清空所有超级岛图片与绑定
     */
    suspend fun clearSuperIslandImages() {
        superIslandImageDao.clearAllBindings()
        superIslandImageDao.clearAllImages()
    }

    // 超级岛镜像过滤相关方法

    suspend fun getEnabledMirrorFilterPackages(): List<String> = superIslandMirrorFilterDao.getEnabledPackages().map { it.packageName }

    suspend fun getAllMirrorFilterPackages(): List<SuperIslandMirrorFilterEntity> = superIslandMirrorFilterDao.getAllPackages()

    suspend fun upsertMirrorFilterPackage(pkg: SuperIslandMirrorFilterEntity) {
        superIslandMirrorFilterDao.upsert(pkg)
    }

    suspend fun setMirrorFilterEnabled(
        packageName: String,
        enabled: Boolean,
    ) {
        superIslandMirrorFilterDao.setEnabled(packageName, enabled)
    }

    suspend fun deleteMirrorFilterPackage(packageName: String) {
        superIslandMirrorFilterDao.delete(packageName)
    }

    // 过滤名单相关方法

    suspend fun getBlackList(): List<BlackListEntryEntity> = filterListDao.getAllBlackList()

    suspend fun replaceBlackList(entries: List<BlackListEntryEntity>) {
        filterListDao.replaceBlackList(entries)
    }

    suspend fun getWhiteList(): List<WhiteListEntryEntity> = filterListDao.getAllWhiteList()

    suspend fun replaceWhiteList(entries: List<WhiteListEntryEntity>) {
        filterListDao.replaceWhiteList(entries)
    }

    suspend fun getLocalFilterEntries(): List<FilterEntryEntity> = filterListDao.getAllFilterEntries()

    suspend fun replaceLocalFilterEntries(entries: List<FilterEntryEntity>) {
        filterListDao.replaceFilterEntries(entries)
    }

    suspend fun getPackageGroups(): List<PackageGroupEntity> = filterListDao.getAllPackageGroups()

    suspend fun getPackageGroupItems(): List<PackageGroupItemEntity> = filterListDao.getAllPackageGroupItems()

    suspend fun replacePackageGroups(
        groups: List<PackageGroupEntity>,
        itemPackages: List<List<String>>,
    ) {
        filterListDao.replacePackageGroups(groups, itemPackages)
    }

    // 应用相关方法

    /**
     * 获取所有应用
     */
    fun getAllApps() = appDao.getAll()

    /**
     * 根据包名获取应用
     */
    suspend fun getAppByPackageName(packageName: String): AppEntity? = appDao.getByPackageName(packageName)

    /**
     * 批量根据包名获取应用
     */
    suspend fun getAppsByPackageNames(packageNames: List<String>): List<AppEntity> = appDao.getByPackageNames(packageNames)

    /**
     * 保存应用
     */
    suspend fun saveApp(app: AppEntity) {
        appDao.insert(app)
    }

    /**
     * 批量保存应用
     */
    suspend fun saveApps(apps: List<AppEntity>) {
        appDao.insertAll(apps)
    }

    /**
     * 删除应用
     */
    suspend fun deleteApp(app: AppEntity) {
        appDao.delete(app)
    }

    /**
     * 根据包名删除应用
     */
    suspend fun deleteAppByPackageName(packageName: String) {
        appDao.deleteByPackageName(packageName)
    }

    /**
     * 获取缺失图标的应用
     */
    fun getIconMissingApps() = appDao.getIconMissingApps()

    /**
     * 更新应用图标
     */
    suspend fun updateAppIcon(
        packageName: String,
        iconBytes: ByteArray,
    ) {
        appDao.updateIcon(packageName, iconBytes, System.currentTimeMillis())
    }

    /**
     * 标记应用图标为缺失
     */
    suspend fun markAppIconAsMissing(packageName: String) {
        appDao.markIconAsMissing(packageName, System.currentTimeMillis())
    }

    /**
     * 获取过期的应用数据
     */
    suspend fun getExpiredApps(expiryTime: Long): List<AppEntity> = appDao.getExpiredApps(expiryTime)

    // 应用设备关联相关方法

    /**
     * 获取所有应用设备关联
     */
    fun getAllAppDevices() = appDeviceDao.getAll()

    /**
     * 根据包名获取应用设备关联
     */
    fun getAppDevicesByPackageName(packageName: String) = appDeviceDao.getByPackageName(packageName)

    /**
     * 批量根据包名获取应用设备关联
     */
    suspend fun getAppDevicesByPackageNames(packageNames: List<String>): List<AppDeviceEntity> = appDeviceDao.getByPackageNames(packageNames)

    /**
     * 根据设备UUID获取应用设备关联
     */
    fun getAppDevicesByDeviceUuid(deviceUuid: String) = appDeviceDao.getByDeviceUuid(deviceUuid)

    /**
     * 获取所有应用设备关联
     */
    fun getAllAppDeviceAssociations() = appDeviceDao.getAll()

    /**
     * 检查应用与设备是否存在关联
     */
    suspend fun checkAppDeviceAssociation(
        packageName: String,
        deviceUuid: String,
    ): AppDeviceEntity? = appDeviceDao.getByPackageNameAndDeviceUuid(packageName, deviceUuid)

    /**
     * 保存应用设备关联
     */
    suspend fun saveAppDeviceAssociation(appDevice: AppDeviceEntity) {
        appDeviceDao.insert(appDevice)
    }

    /**
     * 批量保存应用设备关联
     */
    suspend fun saveAppDeviceAssociations(appDevices: List<AppDeviceEntity>) {
        appDeviceDao.insertAll(appDevices)
    }

    /**
     * 删除应用设备关联
     */
    suspend fun deleteAppDeviceAssociation(appDevice: AppDeviceEntity) {
        appDeviceDao.delete(appDevice)
    }

    /**
     * 根据包名删除应用设备关联
     */
    suspend fun deleteAppDeviceAssociationsByPackageName(packageName: String) {
        appDeviceDao.deleteByPackageName(packageName)
    }

    /**
     * 根据设备UUID删除应用设备关联
     */
    suspend fun deleteAppDeviceAssociationsByDeviceUuid(deviceUuid: String) {
        appDeviceDao.deleteByDeviceUuid(deviceUuid)
    }

    /**
     * 根据包名和设备UUID删除应用设备关联
     */
    suspend fun deleteAppDeviceAssociation(
        packageName: String,
        deviceUuid: String,
    ) {
        appDeviceDao.deleteByPackageNameAndDeviceUuid(packageName, deviceUuid)
    }

    companion object {
        @Volatile
        private var instance: DatabaseRepository? = null

        /**
         * 获取数据库仓库实例（单例模式）
         */
        fun getInstance(context: Context): DatabaseRepository =
            instance ?: synchronized(this) {
                instance ?: DatabaseRepository(AppDatabase.getDatabase(context)).also { instance = it }
            }
    }
}

/** 旧 devices 表迁移行（MIGRATION_8_9 → device_migration 临时表） */
data class DeviceMigrationRow(
    val uuid: String,
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean,
    val displayName: String,
    val lastIp: String,
    val lastPort: Int,
)
