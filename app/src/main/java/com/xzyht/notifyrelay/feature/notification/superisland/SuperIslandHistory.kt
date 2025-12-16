package com.xzyht.notifyrelay.feature.notification.superisland

import android.content.Context
import com.google.gson.Gson
import com.xzyht.notifyrelay.common.data.database.entity.SuperIslandHistoryEntity
import com.xzyht.notifyrelay.common.data.database.repository.DatabaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    val rawPayload: String? = null
)

object SuperIslandHistory {
    private const val MAX_ENTRIES = 600
    private val gson = Gson()

    private val historyFlow = MutableStateFlow<List<SuperIslandHistoryEntry>>(emptyList())
    private val lock = Any()
    @Volatile
    private var initialized = false

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val history = try {
                // 从Room数据库加载历史记录
                val repository = DatabaseRepository.getInstance(context)
                val entities = runBlocking {
                    repository.getSuperIslandHistory()
                }
                // 转换为SuperIslandHistoryEntry
                entities.map { entity: SuperIslandHistoryEntity ->
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
                        rawPayload = entity.rawPayload
                    )
                }
            } catch (_: Exception) {
                emptyList<SuperIslandHistoryEntry>()
            }
            historyFlow.value = history
            initialized = true
            // 异步重建图片引用计数并执行 GC（按时间与条目数限制），避免阻塞加载流程
            try {
                Thread {
                    try {
                        SuperIslandImageStore.rebuildRefCountsAndPrune(context, history)
                        // 额外按阈值进行清理（可调整参数）
                        SuperIslandImageStore.prune(context, maxEntries = 2000, maxAgeDays = 30)
                    } catch (_: Exception) {}
                }.start()
            } catch (_: Exception) {}
        }
    }

    fun historyState(context: Context): StateFlow<List<SuperIslandHistoryEntry>> {
        ensureLoaded(context)
        return historyFlow.asStateFlow()
    }

    fun append(context: Context, entry: SuperIslandHistoryEntry) {
        ensureLoaded(context)
        // 将图片字符串 intern 为引用以避免重复存储
        val interned = SuperIslandImageStore.internAll(context, entry.picMap)
        val sanitizedEntry = entry.copy(picMap = interned.toMap())
        val updated = (historyFlow.value + sanitizedEntry).takeLast(MAX_ENTRIES)
        historyFlow.value = updated
        
        // 保存到Room数据库
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DatabaseRepository.getInstance(context)
                val entities = updated.map { entry: SuperIslandHistoryEntry ->
                    SuperIslandHistoryEntity(
                        id = entry.id,
                        sourceDeviceUuid = entry.sourceDeviceUuid,
                        originalPackage = entry.originalPackage,
                        mappedPackage = entry.mappedPackage,
                        appName = entry.appName,
                        title = entry.title,
                        text = entry.text,
                        paramV2Raw = entry.paramV2Raw,
                        picMap = gson.toJson(entry.picMap),
                        rawPayload = entry.rawPayload
                    )
                }
                repository.saveSuperIslandHistory(entities)
            } catch (_: Exception) {}
        }
    }

    fun clear(context: Context) {
        ensureLoaded(context)
        historyFlow.value = emptyList()
        
        // 清空Room数据库
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DatabaseRepository.getInstance(context)
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
                val repository = DatabaseRepository.getInstance(context)
                repository.clearSuperIslandHistory()
            } catch (_: Exception) {}
        }
        
        try {
            SuperIslandImageStore.prune(context, maxEntries = 0, maxAgeDays = 0)
        } catch (_: Exception) {}
    }
}
