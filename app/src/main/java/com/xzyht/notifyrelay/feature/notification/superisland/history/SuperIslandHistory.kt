package com.xzyht.notifyrelay.feature.notification.superisland.history

import android.content.Context
import com.google.gson.Gson
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity
import com.xzyht.notifyrelay.common.data.database.repository.DatabaseRepository
import com.xzyht.notifyrelay.feature.notification.superisland.image.SuperIslandImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 持久化超级岛历史记录并提供实时状态流以便 UI 订阅。
 */
data class SuperIslandHistoryEntry(
    val id: Long,
    val sourceDeviceUuid: String? = null,
    val originalPackage: String? = null,
    val mappedPackage: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val paramV2Raw: String? = null,
    val picMap: Map<String, String> = emptyMap(),
    val rawPayload: String? = null,
    val featureId: String? = null // 特征ID，用于标识同一座“岛”的一次会话
)

object SuperIslandHistory {
    private const val MAX_ENTRIES = 600
    private val gson = Gson()

    private val historyFlow = MutableStateFlow<List<SuperIslandHistoryEntry>>(emptyList())
    private val lock = Any()
    @Volatile
    private var initialized = false

    private fun ensureLoaded(context: Context, deduplicate: Boolean = true) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            // 异步加载历史记录，避免阻塞主线程
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 从Room数据库加载所有历史记录（只加载摘要，不包含rawPayload）
                    val repository = DatabaseRepository.Companion.getInstance(context)
                    val allEntities = repository.getSuperIslandHistory()

                    // 转换为SuperIslandHistoryEntry
                    val allEntries = allEntities.map { entity: SuperIslandHistoryEntity ->
                        SuperIslandHistoryEntry(
                            id = entity.id,
                            sourceDeviceUuid = entity.sourceDeviceUuid,
                            originalPackage = entity.originalPackage,
                            mappedPackage = entity.mappedPackage,
                            appName = entity.appName,
                            title = entity.title,
                            text = entity.text,
                            paramV2Raw = entity.paramV2Raw,
                            picMap = gson.fromJson(entity.picMap, Map::class.java) as Map<String, String>,
                            rawPayload = null, // 初始加载时不加载rawPayload，按需加载
                            featureId = entity.featureId // 包含特征ID
                        )
                    }

                    // 应用去重逻辑（如果需要）
                    val finalEntries = if (deduplicate) {
                        // 基于特征ID和内容的去重：
                        // - 相同特征ID和内容的记录只保留最新一条
                        // - 相同特征ID但内容不同的记录全部保留
                        allEntries.asSequence()
                            // 按特征ID分组
                            .groupBy { it.featureId }
                            .flatMap { (featureId, entries) ->
                                if (featureId == null || featureId.isEmpty()) {
                                    // 无特征ID的记录全部保留
                                    entries
                                } else {
                                    // 有特征ID的记录，按内容分组，每组只保留最新一条
                                    entries.groupBy { entry ->
                                        // 使用内容作为分组键
                                        listOf(
                                            entry.sourceDeviceUuid,
                                            entry.originalPackage,
                                            entry.mappedPackage,
                                            entry.appName,
                                            entry.title,
                                            entry.text,
                                            entry.paramV2Raw,
                                            entry.picMap
                                        )
                                    }
                                    .mapValues { (_, entries) ->
                                        // 每组只保留最新一条记录（按id降序）
                                        entries.maxByOrNull { it.id } ?: entries.first()
                                    }
                                    .values
                                }
                            }
                            // 按id降序排序
                            .sortedByDescending { it.id }
                            // 限制数量
                            .take(MAX_ENTRIES)
                            .toList()
                    } else {
                        // 不去重，直接限制数量
                        allEntries.takeLast(MAX_ENTRIES)
                    }

                    // 转换为最终的历史记录列表
                    val history = finalEntries

                    // 在主线程更新状态
                    withContext(Dispatchers.Main) {
                        historyFlow.value = history
                        initialized = true
                    }

                    // 异步重建图片引用计数并执行 GC（按时间与条目数限制），避免阻塞加载流程
                    try {
                        SuperIslandImageStore.rebuildRefCountsAndPrune(context, history)
                        // 额外按阈值进行清理（可调整参数）
                        SuperIslandImageStore.prune(context, maxEntries = 2000, maxAgeDays = 30)
                    } catch (_: Exception) {}
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        historyFlow.value = emptyList()
                        initialized = true
                    }
                }
            }
        }
    }

    /**
     * 重新加载历史记录，支持选择是否去重
     */
    fun reloadHistory(context: Context, deduplicate: Boolean = true) {
        synchronized(lock) {
            initialized = false
            ensureLoaded(context, deduplicate)
        }
    }

    fun historyState(context: Context): StateFlow<List<SuperIslandHistoryEntry>> {
        ensureLoaded(context)
        return historyFlow.asStateFlow()
    }

    /**
     * 获取所有历史记录（包含重复记录，用于调试）
     * 注意：这个方法会重新加载所有历史记录，可能会影响性能
     */
    suspend fun getAllHistory(context: Context): List<SuperIslandHistoryEntry> {
        val repository = DatabaseRepository.Companion.getInstance(context)
        val entities = repository.getSuperIslandHistory()
        return entities.map { entity ->
            SuperIslandHistoryEntry(
                id = entity.id,
                sourceDeviceUuid = entity.sourceDeviceUuid,
                originalPackage = entity.originalPackage,
                mappedPackage = entity.mappedPackage,
                appName = entity.appName,
                title = entity.title,
                text = entity.text,
                paramV2Raw = entity.paramV2Raw,
                picMap = gson.fromJson(entity.picMap, Map::class.java) as Map<String, String>,
                rawPayload = null,
                featureId = entity.featureId
            )
        }
    }

    /**
     * 按需加载某条记录的完整内容（包含 rawPayload），用于打开详情时调用。
     */
    suspend fun loadEntryDetail(context: Context, id: Long): SuperIslandHistoryEntry? {
        val repo = DatabaseRepository.Companion.getInstance(context)
        val entity = try {
            repo.getSuperIslandHistoryById(id)
        } catch (_: Exception) {
            null
        }
        return entity?.let { e ->
            SuperIslandHistoryEntry(
                id = e.id,
                sourceDeviceUuid = e.sourceDeviceUuid,
                originalPackage = e.originalPackage,
                mappedPackage = e.mappedPackage,
                appName = e.appName,
                title = e.title,
                text = e.text,
                paramV2Raw = e.paramV2Raw,
                picMap = gson.fromJson(e.picMap, Map::class.java) as Map<String, String>,
                rawPayload = e.rawPayload,
                featureId = e.featureId // 包含特征ID
            )
        }
    }

    fun append(context: Context, entry: SuperIslandHistoryEntry) {
        ensureLoaded(context)
        // 将图片字符串 intern 为引用以避免重复存储
        val interned = SuperIslandImageStore.internAll(context, entry.picMap)
        val sanitizedEntry = entry.copy(picMap = interned.toMap())

        // 基于特征ID和内容的去重逻辑
        val updated = if (entry.featureId != null && entry.featureId.isNotEmpty()) {
            // 检查是否已存在相同特征ID和内容的记录
            val existingIndex = historyFlow.value.indexOfFirst { existingEntry ->
                existingEntry.featureId == entry.featureId && isSameContent(existingEntry, sanitizedEntry)
            }
            if (existingIndex != -1) {
                // 相同特征ID和内容的记录已存在，更新现有记录
                val updatedList = historyFlow.value.toMutableList()
                updatedList[existingIndex] = sanitizedEntry
                updatedList
            } else {
                // 相同特征ID但内容不同，或特征ID不存在，添加新记录
                (historyFlow.value + sanitizedEntry).takeLast(MAX_ENTRIES)
            }
        } else {
            // 无特征ID，直接添加
            (historyFlow.value + sanitizedEntry).takeLast(MAX_ENTRIES)
        }

        historyFlow.value = updated

        // 保存到数据库：只去重真正相同的内容，保留相同特征ID但内容不同的记录
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DatabaseRepository.Companion.getInstance(context)
                val entity = SuperIslandHistoryEntity(
                    id = sanitizedEntry.id,
                    sourceDeviceUuid = sanitizedEntry.sourceDeviceUuid,
                    originalPackage = sanitizedEntry.originalPackage,
                    mappedPackage = sanitizedEntry.mappedPackage,
                    appName = sanitizedEntry.appName,
                    title = sanitizedEntry.title,
                    text = sanitizedEntry.text,
                    paramV2Raw = sanitizedEntry.paramV2Raw,
                    picMap = gson.toJson(sanitizedEntry.picMap),
                    rawPayload = sanitizedEntry.rawPayload,
                    featureId = entry.featureId
                )

                // 直接插入记录，不去重（去重逻辑在应用层实现）
                repository.saveSuperIslandHistory(entity)

                // 清理旧记录，确保数据库中只保留最新的MAX_ENTRIES条
                repository.deleteOldSuperIslandHistory(MAX_ENTRIES)
            } catch (_: Exception) {}
        }
    }

    /**
     * 比较两个SuperIslandHistoryEntry的内容是否相同（忽略id和时间字段）
     */
    private fun isSameContent(entry1: SuperIslandHistoryEntry, entry2: SuperIslandHistoryEntry): Boolean {
        // 比较除id外的所有字段，忽略时间相关字段
        return entry1.sourceDeviceUuid == entry2.sourceDeviceUuid &&
               entry1.originalPackage == entry2.originalPackage &&
               entry1.mappedPackage == entry2.mappedPackage &&
               entry1.appName == entry2.appName &&
               entry1.title == entry2.title &&
               entry1.text == entry2.text &&
               entry1.paramV2Raw == entry2.paramV2Raw &&
               entry1.picMap == entry2.picMap &&
               entry1.featureId == entry2.featureId
    }

    fun clear(context: Context) {
        ensureLoaded(context)
        historyFlow.value = emptyList()

        // 清空Room数据库
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DatabaseRepository.Companion.getInstance(context)
                repository.clearSuperIslandHistory()
            } catch (_: Exception) {}
        }
    }

    /**
     * 清除所有超级岛历史记录，并同步清理相关图片引用计数。
     * 适合作为“清空超级岛历史”设置入口调用。
     */
    fun clearAll(context: Context) {
        ensureLoaded(context)
        historyFlow.value = emptyList()

        // 清空Room数据库
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DatabaseRepository.Companion.getInstance(context)
                repository.clearSuperIslandHistory()
            } catch (_: Exception) {}
        }

        try {
            SuperIslandImageStore.prune(context, maxEntries = 0, maxAgeDays = 0)
        } catch (_: Exception) {}
    }
}